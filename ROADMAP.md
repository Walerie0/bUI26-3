# Rendering roadmap

The integration API provides FIG loading, reusable component definitions, per-screen instances, Minecraft widget integration and namespaced server callbacks. Demo resources, commands and the test suite have been removed from the project.

Remaining rendering work:

1. **Layer hierarchy:** render Frame, Group, Symbol and Child hierarchies with composed transforms, visibility, clipping and group opacity. The current renderer still uses shape lists.
2. **Strokes:** solid/gradient paints and Inside/Center/Outside alignment are implemented. Full vector strokes, independent side weights and in-game fidelity comparisons remain pending.
3. **Effect stack:** local effects use cached raster surfaces. Full GPU execution, isolated groups and stacks without premature flattening remain pending.
4. **Shadows:** Drop Shadow and Inner Shadow have offset, spread, color and approximate Gaussian blur support. Comparison with reference Figma exports remains necessary.
5. **Blur:** Layer Blur uses cached raster surfaces and premultiplied channels. A GPU path and Background Blur remain pending.
6. **Glass:** backdrop sampling, refraction, chromatic dispersion and rounded masks remain pending. Noise and Grain have an initial implementation; exact parameter fidelity is not guaranteed.
7. **Animation:** complete per-layer matching and interpolation of all stroke/effect properties remain pending. Basic variants and supported geometry/paint interpolation are available.

Related work includes image transforms and scale modes, complete blend modes, masks, resource reload handling, keyboard navigation and FPS measurements with several animated components.

Reference: [FigCraft](https://github.com/storytold/figcraft), suggested as an open-source implementation to consult. Its `.fig` importer is best effort; visual validation should still use the original design/export.
