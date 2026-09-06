rootProject.name = "vega"

include(
    ":protocol:java",
    ":server:core",
    ":server:adapter-lsp",
    ":server:adapter-syntax",
    ":server:adapter-fs",
    ":server:app",
    ":fixtures",
)
