# Using bUI from another mod

## 1. Add the dependency

Use the same Java, Minecraft and NeoForge versions as bUI. Publish the library to a repository you control or use its locally generated repository.

In the bUI project, run:

```powershell
.\gradlew.bat publish
```

In the consuming mod's Gradle configuration, point to the actual path of that `repo` directory:

```groovy
repositories {
    maven { url = uri('C:/path/to/bUI26-3/repo') }
}

dependencies {
    implementation 'net.val.bui:bui:1.0.0'
}
```

Alternatively, put the library mod JAR in your consuming project `libs` directory and use `implementation files('libs/bui-1.0.0.jar')`. Install bUI alongside the consuming mod in the runtime `mods` directory. Sources and Javadoc JARs are development aids, not additional runtime mods.

Declare the runtime dependency in the consuming mod's `META-INF/neoforge.mods.toml`:

```toml
[[dependencies.yourmod]]
modId="bui"
type="required"
versionRange="[1.0.0,2.0.0)"
ordering="NONE"
side="BOTH"
```

Replace `yourmod` with your actual mod ID. Server callbacks require bUI on the server as well as the client.

## 2. Place your FIG resource

Store the file in your own mod, not in the bUI library:

```text
src/main/resources/assets/yourmod/bui/components/panel/panel.fig
```

Load it with:

```text
/assets/yourmod/bui/components/panel/panel.fig
```

Use an absolute classpath path with the leading slash. Component folders may differ from the filename. This API loads packaged resources and does not automatically reload definitions when resource packs change.

## 3. Create a component registry

Load each definition once and create independent instances for its placements. A private static final field can be exposed through a getter:

```java
package com.example.yourmod.client;

import net.val.bui.component.BUIComponent;
import net.val.bui.fig.FigLoader;

/** Reusable FIG definitions owned by this mod. */
public final class YourComponents {
    private static final BUIComponent PANEL =
            FigLoader.load("/assets/yourmod/bui/components/panel/panel.fig");

    /** Prevents registry instances. */
    private YourComponents() {}

    /** Returns the shared panel definition; create a fresh instance per screen. */
    public static BUIComponent panel() {
        return PANEL;
    }
}
```

The default loader searches the game context class loader, then the library class loader. For an explicit resource owner, replace the initializer with:

```java
private static final BUIComponent PANEL = FigLoader.load(
        com.example.yourmod.YourMod.class,
        "/assets/yourmod/bui/components/panel/panel.fig");
```

Do not modify shared nested maps, lists or image bytes returned by `getDesign()`. Position, size, animation and callbacks belong to `BUIComponentInstance`.

## 4. Use a BUIScreen

`add(BUIComponent)` creates an instance, registers a widget and returns the instance for configuration:

```java
package com.example.yourmod.client;

import net.minecraft.network.chat.Component;
import net.val.bui.client.screen.BUIScreen;

/** A mod-owned screen containing one interactive bUI component. */
public final class YourScreen extends BUIScreen {
    /** Creates the screen; open it from your mod's client code. */
    public YourScreen() {
        super(Component.literal("Your screen"));
    }

    /** Rebuilds widgets after initialization or resize. */
    @Override
    protected void init() {
        super.init();
        add(YourComponents.panel())
                .pos((width - 240) / 2, (height - 120) / 2)
                .size(240, 120)
                .root()
                .setPressable(true)
                .onClick(this::onClose);
    }
}
```

`add(BUIComponentInstance)` also accepts an already-configured instance. Add components in `init()`, because Minecraft rebuilds widgets after resize. Coordinates use Minecraft GUI units, not framebuffer pixels.

## 5. Keep an existing Screen subclass

Use `BUIWidget` directly when your screen already has another base class. The same pattern works inside an `AbstractContainerScreen` subclass: retain its superclass and existing initialization, then add the widget.

```java
package com.example.yourmod.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.val.bui.client.screen.BUIWidget;
import net.val.bui.component.BUIComponentInstance;

/** An existing Minecraft screen extended with a bUI widget. */
public final class ExistingScreen extends Screen {
    /** Creates the mod screen. */
    public ExistingScreen() {
        super(Component.literal("Existing screen"));
    }

    /** Retains normal initialization and adds the widget. */
    @Override
    protected void init() {
        super.init();
        BUIComponentInstance panel = YourComponents.panel().createInstance()
                .pos(20, 30)
                .size(240, 120);
        panel.root().setPressable(true).onClick(this::onClose);
        addRenderableWidget(new BUIWidget(panel));
    }
}
```

The widget forwards rendering and mouse events automatically. `widget.getInstance()` returns its instance for later layout updates. Do not also render that instance manually, or it will be drawn twice.

## 6. Configure mouse actions

| Method | Behavior |
| --- | --- |
| `pos(x, y)` | Sets the instance GUI position and returns the instance. |
| `size(width, height)` | Sets non-negative display dimensions and returns the instance. |
| `root()` | Gets the component root action. |
| `child(name)` | Looks up a uniquely named imported static layer by its exact Figma name. |
| `setPressable(true)` | Enables mouse targeting; callbacks alone do not make a node interactive. |
| `setEnabled(false)` | Temporarily disables an action. |
| `onHover(callback)` | Receives `true` on entry and `false` on exit. |
| `onPressClient(callback)` | Runs on a left-button press. |
| `onRelease(callback)` | Runs on captured release, including outside the original target. |
| `onClick(callback)` | Runs after a press and release on the same target. |
| `onPressServer(id)` | Requests a registered server action on press, not confirmed click. |

For imported static hierarchies, `instance.child("Panel").child("Confirm")` narrows the lookup to an ancestor. Named targets must exist in the imported node set. Animated variants currently use root action routing; arbitrary per-child interaction across variants is not supported yet.

Mouse interaction is implemented. Full keyboard navigation and focus behavior are not yet a complete replacement for vanilla widgets.

## 7. Register server actions

Register each action once during common mod initialization. Use your mod ID as its namespace; duplicates are rejected. Do not register actions whenever a screen opens.

```java
package com.example.yourmod;

import net.neoforged.fml.common.Mod;
import net.val.bui.network.BUIServerActions;

/** Common entry point without client screen references. */
@Mod(YourMod.MOD_ID)
public final class YourMod {
    public static final String MOD_ID = "yourmod";

    /** Registers this mod's server menu action once. */
    public YourMod() {
        BUIServerActions.registerAction(MOD_ID + ":close_menu", player -> {
            if (!player.isAlive()) return;
            player.closeContainer();
        });
    }
}
```

Bind it from client screen initialization:

```java
panel.root()
        .setPressable(true)
        .onPressServer("yourmod:close_menu");
```

The payload contains only the ID. The server executes registered callbacks and ignores unknown IDs. IDs must have at most 128 lowercase ASCII characters and follow `namespace:path` syntax. A valid ID is not authorization: validate permissions, distance, inventory/menu state and request frequency as needed for your action. Register common callbacks on both physical distributions so integrated servers behave consistently.

## 8. Handle diagnostics

Errors include the originating class, method, problem and recovery guidance:

```text
[CLASS] BUIComponentInstance [@size()] -> Negative component size -> Possible solution: Pass a non-negative width and height to size(width, height).
```

Catch `IllegalArgumentException` for invalid configuration, `IllegalStateException` for invalid design/runtime state, and `UncheckedIOException` for I/O when your UI has a defined fallback. Original I/O and image-upload causes are preserved. Exceptions from your callbacks remain your mod's responsibility.

## Current limits

bUI is an integration API, not a full Figma renderer yet. See [the rendering roadmap](../ROADMAP.md). Static import accepts supported rectangles; component-set import handles supported variant child shapes. Unsupported frames, text and vectors may be rejected with a recovery hint. Glass, Background Blur, complete group compositing and full effect animation remain pending.

The Javadoc/source artifacts document the integration methods. `fig.kiwi`, `model`, `animation` internals and `client.render` implementation details are not the recommended entry points for consuming mods.
