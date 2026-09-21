# Getting Started

### PDF to PNG rendering

New to calling native code from Java, or to Rust? Read [docs/java-rust-ffi.md](docs/java-rust-ffi.md)
first - it walks one request through every layer and explains the concepts the code relies on.

This app renders PDF pages to PNG in-process using Google's pdfium through a Rust shim
(`native/pdf-render`, see its README) called via the Java FFM API. Requirements: a Rust
toolchain (`cargo`) and `curl` on the build machine; nothing extra at runtime — both native
libraries are bundled into the jar.

Endpoints (multipart field `file`, page numbers are 1-based):

```bash
# one page as PNG (X-Pdf-Page-Count header carries the total)
curl -F file=@doc.pdf 'localhost:8080/api/pdf/png?page=1&dpi=150' -o page1.png
# every page, zipped as page-001.png, page-002.png, ...
curl -F file=@doc.pdf 'localhost:8080/api/pdf/png/all?dpi=150' -o pages.zip
# page count only
curl -F file=@doc.pdf localhost:8080/api/pdf/page-count
```

Render concurrency: pdfium is single-threaded, so the build bundles N independent copies of
the native libraries (`-Dpdf.render.slots=N`, default 6, ~9 MB each) and the service keeps a
pool of N renderers guarded by a semaphore. Requests beyond N queue for
`pdf.render.acquire-timeout` (default 30s) and then get `503` with `Retry-After`.

```bash
./mvnw package -Dpdf.render.slots=6          # bundle 6 renderer slots
java -jar target/demo-*.jar --pdf.render.concurrency=4   # use only 4 of them at runtime
```

Linux deployment: cross-compile from the Mac with `./mvnw package -Pcross-linux-x86_64` (needs
`zig` + `cargo-zigbuild`, see `native/pdf-render/README.md`), then `docker build -t demo-pdf .`
using the provided `Dockerfile`. The jar picks the right `native/<platform>/` at startup, so one
jar can carry macOS and Linux slots.

Configuration (`application.properties`, all optional): `pdf.render.concurrency` (0 = all
bundled slots), `pdf.render.acquire-timeout`, `pdf.render.default-dpi`, `pdf.render.max-dpi`,
`pdf.render.library-path`, `pdf.render.pdfium-path`.

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.1.1/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.1.1/maven-plugin/build-image.html)
* [Spring Web](https://docs.spring.io/spring-boot/4.1.1/reference/web/servlet.html)

### Guides
The following guides illustrate how to use some features concretely:

* [Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/)
* [Serving Web Content with Spring MVC](https://spring.io/guides/gs/serving-web-content/)
* [Building REST services with Spring](https://spring.io/guides/tutorials/rest/)

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.

