plugins {
    id("java")
}

group = "org.steventc"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.43.0.0")
    implementation("org.jooq:jooq:3.18.6")
    implementation("org.jooq:jooq-meta:3.18.6")
    implementation("org.jooq:jooq-codegen:3.18.6")
    implementation("tech.tablesaw:tablesaw-core:0.43.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}