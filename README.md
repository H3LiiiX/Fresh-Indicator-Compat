# Fresh Indicator Compat

This mod acts as a bridge between `Fresh Armor Bar` and `Health Indicator Next`. 

### How it Works
1. It uses a Mixin (`ArmorIndicatorRendererMixin`) to inject into `Health Indicator Next`'s `ArmorIndicatorRenderer` which draws armor icons above players' heads.
2. It cancels the default rendering of vanilla armor icons.
3. It resolves the specific `EquipmentSlots` for the `LivingEntity` being rendered.
4. It dynamically fetches the custom texture identifiers (`Identifier`) that `Fresh Armor Bar` uses for the equipped armor types (diamond, netherite, modded, etc.).
5. It renders these split 9x9 strip quads onto the 3D billboard (`VertexConsumer`), effectively rendering Fresh Armor Bar's styles seamlessly into Health Indicator Next's framework.

### Setup
To build this, you can drop this directory into your Fabric workspace, or configure the dependencies in `build.gradle` to resolve `health_indicator_next` and `fresh_armor_bar` using local `modImplementation files(...)` paths or a local Maven repository.
