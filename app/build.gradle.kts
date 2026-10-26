// Composition root and entry point. The only module that knows every other module.
plugins {
    id("nothingeats.kotlin-service")
}

application {
    mainClass = "io.github.slashncode.nothingeats.ordering.app.MainKt"
}

dependencies {
    implementation(platform(libs.nothingeats.platform.bom))
    implementation(project(":adapters"))

    testImplementation(libs.nothingeats.platform.testing)
    // Fake delivery and loyalty services in the flow test speak the real contracts.
    testImplementation("io.github.slashncode.nothingeats.delivery:grpc-api")
    testImplementation("io.github.slashncode.nothingeats.loyalty:grpc-api")
    testImplementation(libs.konsist)
}
