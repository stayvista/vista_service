rootProject.name = "stayvista-services"

include(
    ":apps:gateway",
    ":apps:catalog",
    ":apps:booking",
    ":apps:search",
    ":apps:ticket",
    ":apps:geo",
    ":apps:chatbot",
    ":libs:common-web",
    ":libs:common-db",
    ":libs:common-observability",
)
