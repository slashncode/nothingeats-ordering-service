// HTTP routes, persistence, Kafka and the gRPC clients (delivery, loyalty), behind the application's ports.
plugins {
    id("nothingeats.kotlin-base")
    id("nothingeats.kotlinx-serialization")
}

dependencies {
    implementation(platform(libs.nothingeats.platform.bom))
    api(project(":application"))
    api(libs.nothingeats.platform.http)
    api(libs.nothingeats.platform.persistence)
    api(libs.nothingeats.ktor.posthog)
    api(libs.nothingeats.platform.kafka)
    api(libs.nothingeats.platform.grpc)
    // The delivery and loyalty services' gRPC contracts, composite builds of the sibling checkouts (record 032).
    implementation("io.github.slashncode.nothingeats.delivery:grpc-api")
    implementation("io.github.slashncode.nothingeats.loyalty:grpc-api")
    implementation(libs.exposed.java.time)

    testImplementation(testFixtures(project(":application")))
    testImplementation(libs.nothingeats.platform.testing)
    testImplementation(libs.ktor.server.test.host)
}
