// KOTRAIL_CONFIG: rules.idempotent=on
// RENDER_DIAGNOSTICS_FULL_TEXT
@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

import com.kitakkun.kotrail.idempotency.Idempotent
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class Session {
    var lastRefresh = 0L
    var startedAt = Clock.System.now()
    var id = ""
    var seed = 0
    val ids = mutableMapOf<String, String>()

    // A value that differs per call, stored outside the function.
    @Idempotent
    fun stores(key: String) {
        <!KOTRAIL_NOT_IDEMPOTENT!>lastRefresh = System.currentTimeMillis()<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>lastRefresh = System.nanoTime() / 1000<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>startedAt = Clock.System.now()<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>id = UUID.randomUUID().toString()<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>id = Uuid.random().toString()<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>seed = Random.nextInt()<!>
        val token = UUID.randomUUID().toString()
        <!KOTRAIL_NOT_IDEMPOTENT!>id = token<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>ids[key] = token<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>ids.put(key, token)<!>
    }

    // Read, compared or kept in a local, the same values do no harm.
    @Idempotent
    fun reads(key: String): Boolean {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefresh
        val local = UUID.randomUUID()
        println(local)
        ids[key] = key
        return elapsed > 1000 && Random.nextBoolean()
    }
}

/* GENERATED_FIR_TAGS: additiveExpression, andExpression, annotationUseSiteTargetFile, assignment, classDeclaration,
classReference, comparisonExpression, flexibleType, functionDeclaration, integerLiteral, javaFunction, localProperty,
multiplicativeExpression, nullableType, propertyDeclaration, stringLiteral */
