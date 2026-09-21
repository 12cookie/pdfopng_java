# Runtime image for the Linux build. Build the jar first with the Linux slots included:
#
#   ./mvnw package -Pcross-linux-x86_64          # or -Pcross-linux-aarch64, or both
#   docker build -t demo-pdf .
#   docker run --rm -p 8080:8080 demo-pdf
#
# Must be a glibc-based image (Debian/Ubuntu/RHEL family): the prebuilt libpdfium.so needs
# glibc >= 2.16 and libgcc_s, and does not run on Alpine/musl. Temurin's default tag is Ubuntu.
FROM eclipse-temurin:25-jre

# The native libraries are extracted from the jar into java.io.tmpdir at startup (~9 MB per slot).
WORKDIR /app
COPY target/demo-*.jar /app/app.jar

EXPOSE 8080
# Enable-Native-Access is already in the jar manifest; the flag here just makes it explicit.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
