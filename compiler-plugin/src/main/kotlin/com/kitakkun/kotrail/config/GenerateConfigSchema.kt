package com.kitakkun.kotrail.config

import java.io.File

/** Writes [ConfigSchema.jsonSchema] to the path given, for `./gradlew :compiler-plugin:generateConfigSchema`. */
fun main(args: Array<String>) {
    val target = File(args.single())
    target.parentFile.mkdirs()
    target.writeText(ConfigSchema.jsonSchema())
    println("wrote ${target.path}")
}
