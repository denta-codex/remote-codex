plugins { id("org.jetbrains.kotlin.jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
