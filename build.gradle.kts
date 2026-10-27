plugins { java }
group = "me.roanh"
version = "1.2.0"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.222-stable")
    testImplementation("io.papermc.paper:paper-api:26.3.build.222-stable")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.3:4.118.0")
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}
tasks.processResources {
    filteringCharset = "UTF-8"
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}
tasks.test {
    useJUnitPlatform()
    systemProperty("net.bytebuddy.safe", "false")
}
tasks.jar { archiveBaseName.set("BoysBV") }