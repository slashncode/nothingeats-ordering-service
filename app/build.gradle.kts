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
    // A fake delivery service in the flow test speaks the real contract.
    testImplementation("io.github.slashncode.nothingeats.delivery:grpc-api")
    testImplementation(libs.konsist)
}
