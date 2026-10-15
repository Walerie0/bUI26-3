# bUI — NeoForge component API

-- In search of a feature i haven't implemented yet? is it NOT in the roadmap? then contact me on discord and ask for it: waleriew

bUI is a library for other NeoForge mods to load FIG designs and place independent UI components inside their own Minecraft screens.

Requires **Java 25**, **Minecraft 26.3** and **NeoForge 26.3.0.77**. The library ships no demo screens, commands, test actions or FIG assets.

## Add a component to your mod

Place your FIG file in your own resources:

```text
src/main/resources/assets/YOURMODID/bui/components/component_name/filename.fig
```

Replace `YOURMODID` with your actual lowercase mod ID. For example:

```text
src/main/resources/assets/yourmod/bui/components/panel/panel.fig
```

Load the definition once in your component registry:

```java
private static final BUIComponent PANEL =
        FigLoader.load("/assets/yourmod/bui/components/panel/panel.fig");
```

The leading `/` refers to the resource root; do not include `src/main/resources` in the argument. Paths and filenames are case-sensitive. For an explicit resource-owning mod module, use `FigLoader.load(YourMod.class, path)`.

Inside your `BUIScreen.init()` method:

```java
super.init();
add(YourComponents.panel())
        .pos(100, 50)
        .size(240, 120)
        .root()
        .setPressable(true)
        .onClick(this::onClose);
```

You can also keep an existing `Screen` subclass and register `new BUIWidget(instance)` with `addRenderableWidget(...)`. Each placement needs its own instance; the loaded component definition can be shared.

See [the API guide](docs/API.md) for complete registry and screen examples, dependency setup, server callbacks and lifecycle rules.

## Build and distribution

```powershell
.\gradlew.bat clean build
```

The build creates these artifacts in `build/libs`:

- `bui-1.0.0.jar`: library mod for development and runtime.
- `bui-1.0.0-sources.jar`: sources with English Javadoc.
- `bui-1.0.0-javadoc.jar`: generated API reference.

The browsable reference is generated at `build/docs/javadoc/index.html`. Every explicitly declared method and constructor has English Javadoc, including internal methods. No test suite, sample FIG resources or demo command is included.

To publish into this project's local `repo` directory:

```powershell
.\gradlew.bat publish
```

The Maven coordinates are `net.val.bui:bui:1.0.0`. No remote repository is configured or implied.

## Diagnostics

Library-generated argument, state and resource errors use this format:

```text
[CLASS] FigLoader [@load()] -> FIG resource not found: /assets/yourmod/bui/components/panel/panel.fig -> Possible solution: Place the file under src/main/resources/assets/yourmod/bui/components/panel/panel.fig; check spelling and case, or use load(YourMod.class, path) for an explicit resource owner.
```

`IllegalArgumentException` describes invalid API input. `IllegalStateException` describes unsupported or inconsistent design/runtime state. `UncheckedIOException` preserves archive and decompression failures with their original cause. bUI does not register a testing server action or send testing messages to players.

## Supported scope

The public integration API is usable independently of the removed demos. Rendering fidelity remains limited: supported rectangle designs and imported component-set variants work, while arbitrary frame/text/vector imports and complete hierarchical compositing are not yet supported. Glass and Background Blur remain unimplemented; local effects use cached raster surfaces rather than a complete GPU effect pipeline. See [the rendering roadmap](ROADMAP.md).

## Packages

| Package | Purpose |
| --- | --- |
| `component` | Reusable definitions, per-screen instances and mouse actions. |
| `fig` | Public resource loading and internal FIG variant extraction. |
| `client.screen` | `BUIScreen` and `BUIWidget` integration helpers. |
| `network` | Namespaced server action registration and validated payloads. |
| `error` | Contextual diagnostics and API argument validation. |
| `animation` | Instance animation state and interpolation. |
| `model` | Internal decoded metadata, paint sampling and geometry. |
| `fig.kiwi` | Internal binary/schema decoding. |
| `client.render` | Internal GUI states and texture caches. |

Import client screen/render classes only from code loaded on the client. Server action registration belongs in common mod initialization.
