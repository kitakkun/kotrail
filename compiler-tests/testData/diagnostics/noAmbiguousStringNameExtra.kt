// KOTRAIL_CONFIG: rules.noAmbiguousStringName=on, rules.noAmbiguousStringName.extraApis=url:net.open,sql:net.Dao.query#1,channel:net.subscribe, rules.noAmbiguousStringName.extraKinds=url=route, rules.noAmbiguousStringName.extraKinds=channel=channel,topic
// RENDER_DIAGNOSTICS_FULL_TEXT
package net

import java.util.UUID

fun open(endpoint: String) = endpoint.length

class Dao {
    fun query(limit: Int, text: String) = text.length + limit
}

fun subscribe(name: String) = name.length

// Reported: a project's own function, mapped to the url kind by extraApis.
fun home(<!KOTRAIL_STRING_NAME_HIDES_KIND!>target<!>: String) = open(target)

// Not reported: 'route' is a url word that extraKinds adds.
fun page(pageRoute: String) = open(pageRoute)

// Reported: #1 is the second parameter of Dao.query; the first is no text.
fun find(dao: Dao, <!KOTRAIL_STRING_NAME_HIDES_KIND!>filter<!>: String, count: Int) = dao.query(count, filter)

// Reported: a project's own kind, with its own words.
fun listen(<!KOTRAIL_STRING_NAME_HIDES_KIND!>events<!>: String) = subscribe(events)

// Not reported: 'topic' is a word of that kind.
fun listenTo(eventTopic: String) = subscribe(eventTopic)

// Reported: extraApis keeps the defaults.
fun session(<!KOTRAIL_STRING_NAME_HIDES_KIND!>token<!>: String) = UUID.fromString(token)

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, flexibleType, functionDeclaration, javaFunction */
