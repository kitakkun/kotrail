package com.kitakkun.kotrail.fir

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How far a private helper sits from its only caller, from a model of the file that knows
 * nothing of FIR: every declaration as a key, the lines it occupies, what it references, and
 * where it sits.
 *
 * A reader who follows a call into a helper moves their eyes from the caller to the helper; every
 * line between the two that belongs neither to the pair nor to the caller's closure (the
 * same-file declarations the caller references, transitively) is unrelated code they scroll
 * past. The properties of the caller's own class and of its companion object are not in the
 * closure: a reader takes a field or a constant as vocabulary, the way they take a parameter,
 * rather than as a place they go to.
 *
 * The same model tells what a fix that moves a helper next to its caller takes along
 * ([movedTogether]), so that the move leaves nothing for either check to report.
 */
object HelperDistance {
    enum class Kind { FUNCTION, PROPERTY, CLASS }

    /**
     * One declaration of the file. [ranges] are the lines it occupies, 1-based and inclusive: one
     * range for a function or a property, the header and the `init` blocks for a class.
     * [references] are the keys of what it references directly; keys that are no member of the
     * file are ignored. [owner] is the key of the class that declares it, `null` at the top level;
     * [companionOf] is, for a companion object, the key of the class that holds it. [isObject] is
     * true for an `object` declaration, companion objects included. [isPrivate] is true for a
     * private declaration, in a class or in the file. [isConstant] is true for a property a
     * reader looks up by name, a `const val` or a `val` whose initializer is a constant
     * expression. [builds] is, for a function that returns a class of the same file, the lines
     * that class declaration spans, `null` otherwise.
     */
    class Member<K>(
        val key: K,
        val ranges: List<IntRange>,
        val references: Set<K>,
        val kind: Kind,
        val owner: K?,
        val companionOf: K?,
        val isObject: Boolean,
        val isPrivate: Boolean,
        val isConstant: Boolean,
        val builds: IntRange?,
    ) {
        val start: Int get() = ranges.minOf { it.first }
        val lines: Set<Int> get() = ranges.flatMap { it.toList() }.toSet()
    }

    /**
     * The only caller of a helper that sits far from it, the raw distance between their first
     * lines, the [effectiveDistance], and whether the helper sits [above] its caller.
     */
    class FarHelper<K>(val caller: Member<K>, val distance: Int, val effectiveDistance: Int, val above: Boolean)

    /** How near, in lines, a factory has to be to the class it returns to count as placed by that type. */
    const val FACTORY_DISTANCE = 30

    /**
     * The only caller of [target] when [target] is a helper (see [isHelper]) that sits far from
     * that caller, `null` otherwise. Far means both: the [effectiveDistance] exceeds
     * [maxCallerDistance], and at least [maxHelperNoise] percent of the lines from the caller to
     * the helper belong to neither the caller nor its closure (see [helperNoise]). The caller's
     * other helpers lying between the two are its own story, not noise: of three helpers written
     * below their caller in call order, the third is no worse placed than the first.
     *
     * A helper is looked for below its caller; above it only for a private top-level property,
     * since a private function above its first caller is the order check's. A helper with no
     * caller or several is not far in this sense. A constant ([Member.isConstant]) is looked up
     * by name wherever it sits, and a factory declared within [FACTORY_DISTANCE] lines of the
     * class it returns ([Member.builds]) is placed by that type rather than by its caller: neither
     * is far.
     *
     * A run of helpers with the same only caller, written one after another, is one unit measured
     * from its member nearest the caller ([placedByRun]): six icons a screen draws cannot all sit
     * next to it, and when the first is placed, the rest are.
     */
    fun <K> farFromCaller(target: Member<K>, members: Map<K, Member<K>>, maxCallerDistance: Int, maxHelperNoise: Int): FarHelper<K>? {
        if (!isHelper(target, members) || target.isConstant || placedByType(target)) return null
        val caller = callersOf(target, members).singleOrNull() ?: return null
        val above = caller.start >= target.start
        if (above && !(target.kind == Kind.PROPERTY && target.owner == null)) return null
        if (!isFar(target, caller, members, maxCallerDistance, maxHelperNoise)) return null
        if (placedByRun(target, caller, members, maxCallerDistance, maxHelperNoise)) return null
        return FarHelper(caller, abs(target.start - caller.start), effectiveDistance(target, caller, members), above)
    }

    /** Whether [helper] is past [maxCallerDistance] from [caller] with at least [maxHelperNoise] percent of unrelated lines between. */
    private fun <K> isFar(helper: Member<K>, caller: Member<K>, members: Map<K, Member<K>>, maxCallerDistance: Int, maxHelperNoise: Int): Boolean =
        effectiveDistance(helper, caller, members) > maxCallerDistance &&
            (helperNoise(helper, caller, members) * 100).roundToInt() >= maxHelperNoise

    /**
     * Whether [target] belongs to a run of [caller]'s helpers whose member nearest [caller] is
     * placed. Walking from [target] toward [caller], each next declaration of the same container
     * has to be another helper whose only caller is [caller]; the walk ends placed at the first
     * one that is not far ([isFar]), and unplaced at any other declaration, the caller included.
     */
    private fun <K> placedByRun(target: Member<K>, caller: Member<K>, members: Map<K, Member<K>>, maxCallerDistance: Int, maxHelperNoise: Int): Boolean {
        val siblings = members.values.filter { it.owner == target.owner }
        var current = target
        while (true) {
            val next = if (caller.start > current.start) {
                siblings.filter { it.start > current.start }.minByOrNull { it.start }
            } else {
                siblings.filter { it.start < current.start }.maxByOrNull { it.start }
            } ?: return false
            if (!isHelper(next, members) || callersOf(next, members).singleOrNull()?.key != caller.key) return false
            if (!isFar(next, caller, members, maxCallerDistance, maxHelperNoise)) return true
            current = next
        }
    }

    /**
     * The distance between the first lines of [helper] and [caller], less the lines between them
     * that belong to the caller's closure other than the helper: the caller's other helpers.
     */
    fun <K> effectiveDistance(helper: Member<K>, caller: Member<K>, members: Map<K, Member<K>>): Int {
        val low = minOf(helper.start, caller.start)
        val high = maxOf(helper.start, caller.start)
        val related = closureOf(caller, members).filter { it.key != helper.key }.flatMap { it.lines }.toSet()
        return high - low - related.count { it in (low + 1) until high }
    }

    /**
     * The share of the lines from the first line of [caller] or [helper] to the last line of
     * either that belongs to none of the two and none of the caller's closure, rounded to two
     * decimals: the unrelated code a reader scrolls past between the two.
     */
    fun <K> helperNoise(helper: Member<K>, caller: Member<K>, members: Map<K, Member<K>>): Double {
        val pair = listOf(helper, caller)
        val first = pair.minOf { member -> member.ranges.minOf { it.first } }
        val last = pair.maxOf { member -> member.ranges.maxOf { it.last } }
        val related = (pair + closureOf(caller, members)).flatMap { it.lines }.toSet().count { it in first..last }
        val span = last - first + 1
        return if (span <= related) 0.0 else ((span - related) * 100.0 / span).roundToInt() / 100.0
    }

    /** Whether [target] returns a class of the file declared within [FACTORY_DISTANCE] lines of it, before or after. */
    private fun <K> placedByType(target: Member<K>): Boolean {
        val type = target.builds ?: return false
        return target.ranges.any { range -> range.first - type.last <= FACTORY_DISTANCE && type.first - range.last <= FACTORY_DISTANCE }
    }

    /**
     * The same-file declarations [target] references, transitively, without [target] itself and
     * without the properties of [target]'s own class and of its companion object, which are
     * vocabulary: they neither count nor lead further.
     */
    private fun <K> closureOf(target: Member<K>, members: Map<K, Member<K>>): List<Member<K>> {
        val home = homeOf(target, members)
        val seen = LinkedHashMap<K, Member<K>>()
        val pending = ArrayDeque(target.references)
        while (pending.isNotEmpty()) {
            val key = pending.removeFirst()
            if (key == target.key || key in seen) continue
            val member = members[key] ?: continue
            if (member.kind == Kind.PROPERTY && member.owner != null && member.owner in home) continue
            seen[key] = member
            pending.addAll(member.references)
        }
        return seen.values.toList()
    }

    /** [target]'s class, that class's companion object, and, for a member of a companion, the class that holds it. */
    private fun <K> homeOf(target: Member<K>, members: Map<K, Member<K>>): Set<K> {
        val owner = target.owner ?: return emptySet()
        return buildSet {
            add(owner)
            members[owner]?.companionOf?.let(::add)
            members.values.filter { it.companionOf == owner }.forEach { add(it.key) }
        }
    }

    /**
     * Whether [target] is a helper whose placement is a choice: a private function, at the top
     * level or in a class, or a private top-level property. The members of an `object` or of a
     * companion object are constants and entry points a reader looks up by name, and the
     * properties of a class are its fields, laid out at the top by convention; neither is a helper.
     */
    private fun <K> isHelper(target: Member<K>, members: Map<K, Member<K>>): Boolean {
        if (!target.isPrivate) return false
        return when (target.kind) {
            Kind.FUNCTION -> target.owner == null || members[target.owner]?.isObject != true
            Kind.PROPERTY -> target.owner == null
            Kind.CLASS -> false
        }
    }

    /** The declarations of the file that reference [target] directly, without [target] itself. */
    private fun <K> callersOf(target: Member<K>, members: Map<K, Member<K>>): List<Member<K>> =
        members.values.filter { it.key != target.key && target.key in it.references }

    /**
     * The declarations a fix moves when it moves [moved] right after the lines [anchor], or right
     * before them when [before]: [moved] in its order, then the private functions of the same
     * container that only the group calls, transitively, and that the move would leave out of
     * place ([isOutOfPlace]) in the layout it leaves ([afterMove]). Each one out of place joins
     * the group and the layout is taken again, so that the functions only it calls can follow
     * it; one that stays keeps those where they are. The functions that join come after
     * [moved], each after one of its callers ([inCallOrder]).
     */
    fun <K> movedTogether(
        moved: List<Member<K>>,
        anchor: IntRange,
        before: Boolean,
        members: Map<K, Member<K>>,
        maxCallerDistance: Int,
        maxHelperNoise: Int,
    ): List<Member<K>> {
        val owner = moved.first().owner
        var group = moved
        while (true) {
            val keys = group.mapTo(HashSet()) { it.key }
            val layout = afterMove(group, anchor, before, members)
            val outOfPlace = members.values.filter { candidate ->
                candidate.key !in keys && candidate.kind == Kind.FUNCTION && candidate.isPrivate && candidate.owner == owner &&
                    callersOf(candidate, members).let { callers -> callers.isNotEmpty() && callers.all { it.key in keys } } &&
                    isOutOfPlace(layout.getValue(candidate.key), layout, maxCallerDistance, maxHelperNoise)
            }
            if (outOfPlace.isEmpty()) return group
            group = inCallOrder(moved, group + outOfPlace, members)
        }
    }

    /**
     * Whether the function [target] is out of place in [layout]: above the first declaration that
     * calls it, short of mutual recursion with that one, as the order check reports, or far from
     * its only caller ([farFromCaller]) while [maxCallerDistance] is not 0, which switches the
     * distance check off.
     */
    private fun <K> isOutOfPlace(target: Member<K>, layout: Map<K, Member<K>>, maxCallerDistance: Int, maxHelperNoise: Int): Boolean {
        val firstCaller = callersOf(target, layout).minByOrNull { it.start } ?: return false
        if (firstCaller.start > target.start && firstCaller.key !in target.references) return true
        return maxCallerDistance > 0 && farFromCaller(target, layout, maxCallerDistance, maxHelperNoise) != null
    }

    /**
     * [group] with [moved] first, then the others one at a time: the first in source order with a
     * caller already placed, or, in a cycle, the first in source order.
     */
    private fun <K> inCallOrder(moved: List<Member<K>>, group: List<Member<K>>, members: Map<K, Member<K>>): List<Member<K>> {
        val placed = moved.mapTo(HashSet()) { it.key }
        val ordered = moved.toMutableList()
        val rest = group.filter { it.key !in placed }.sortedBy { it.start }.toMutableList()
        while (rest.isNotEmpty()) {
            val next = rest.firstOrNull { member -> callersOf(member, members).any { it.key in placed } } ?: rest.first()
            ordered += next
            placed += next.key
            rest -= next
        }
        return ordered
    }

    /**
     * [members] as they sit once [block] is cut from its place and inserted, in its order, right
     * after the lines [anchor], or right before them when [before], blank lines aside: every other
     * line moves up by the moved lines that were above it, and down by those inserted above it.
     */
    private fun <K> afterMove(block: List<Member<K>>, anchor: IntRange, before: Boolean, members: Map<K, Member<K>>): Map<K, Member<K>> {
        val cut = block.flatMapTo(HashSet()) { it.lines }
        val insertedAfter = if (before) anchor.first - 1 else anchor.last
        fun shifted(line: Int): Int = line - cut.count { it < line } + if (line > insertedAfter) cut.size else 0
        val relocated = HashMap<K, List<IntRange>>()
        var next = insertedAfter - cut.count { it <= insertedAfter } + 1
        for (member in block) {
            val by = next - member.start
            relocated[member.key] = member.ranges.map { (it.first + by)..(it.last + by) }
            next += member.lines.size
        }
        return members.mapValues { (key, member) ->
            Member(
                key, relocated[key] ?: member.ranges.map { shifted(it.first)..shifted(it.last) }, member.references, member.kind,
                member.owner, member.companionOf, member.isObject, member.isPrivate, member.isConstant,
                member.builds?.let { shifted(it.first)..shifted(it.last) },
            )
        }
    }
}
