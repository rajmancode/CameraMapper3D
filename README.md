# CameraMapper3D

An Android app that turns the phone camera into a 3D scanner for a site. You walk around and the
app builds a coloured 3D mesh of the space live, on top of the camera view. You can then measure
the model and export it to other tools.

## Features

- **Live mesh scanning.** The mesh grows on top of the camera feed as you move. You can pause,
  resume or reset at any time.
- **Coloured model.** Every surface is coloured from the camera images, averaged across all the
  frames that saw it.
- **Measurements.** Tap two points on the finished model to get the straight-line distance, plus
  its horizontal and vertical parts. ARCore's world is aligned with gravity.
- **Export.** Share a scan as **PLY** (binary, with colours and normals) or **OBJ** (with
  per-vertex colours) to MeshLab, CloudCompare, Blender, or CAD/BIM tools. Units are metres and
  Y is up.
- **Saved scans.** Every scan is saved on the phone. Open it again from the start screen.

## Requirements

- An Android 8.0+ phone that supports the
  [ARCore Depth API](https://developers.google.com/ar/devices). Most recent mid- to high-end
  phones do. Phones with a time-of-flight (ToF) sensor give the best results.
- Google Play Services for AR. The app asks to install it if it's missing.

## Building

```bash
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test           # reconstruction unit tests (plain JVM, no device needed)
```

You need JDK 17+ and the Android SDK (platform 35). Android Studio sets these up for you.
Otherwise, point `local.properties` at your SDK with `sdk.dir=/path/to/android-sdk`.

## How to scan well

1. Choose a **detail level** on the start screen:
   - Fine (1.5 cm voxels) for objects and small rooms.
   - Standard (2 cm) for rooms.
   - Large site (4 cm) for buildings and outdoors. It covers four times more area for the same memory.
2. Tap **Start**, then move **slowly**. Stay about 0.5 to 3 m from surfaces and look at each
   surface from more than one angle.
3. Good light and textured surfaces help tracking. Plain white walls and glass are hard for any
   phone depth sensor.
4. **Accurate depth** (on by default) uses ARCore's raw depth with per-pixel confidence. You get
   fewer artefacts, but holes take longer to fill. Turn it off to use ARCore's smoothed,
   hole-filled depth, which fills quickly but is softer.
5. Tap **Finish**. The final mesh is built, saved and opened in the viewer.

## How it works

```
ARCore frame ──► depth (mm) + confidence + camera colour + intrinsics + pose
                    │
                    ▼  FusionEngine (background thread)
              TsdfVolume  ── sparse 8³ voxel blocks, truncated signed distance + colour
                    │
                    ▼
            SurfaceNetsMesher ── per-block meshes (live, incremental) / welded final mesh
                    │
        ┌───────────┼──────────────┐
        ▼           ▼              ▼
   live AR overlay  PLY/OBJ export  MeshRaycaster (tap-to-measure)
```

- **`core/`** is a plain Kotlin library with no Android dependencies. It holds all the 3D
  reconstruction code:
  - `TsdfVolume`: projective TSDF fusion into a sparse hash of voxel blocks. Memory grows with
    the scanned surface area, not with the size of the site.
  - `SurfaceNetsMesher`: extracts the surface. Each block meshes on its own, so the live preview
    only re-meshes the blocks that changed.
  - `MeshRaycaster`: ray picking with a uniform grid, used for measuring.
  - `PlyWriter`, `PlyReader` and `ObjWriter`: export and reload.
- **`app/`** is the Android app:
  - `ScanActivity`: the ARCore session and live rendering.
  - `ArFrameConverter`: turns ARCore frames into fusion input.
  - `ViewerActivity`: orbit viewer, measuring and sharing.
  - `gl/`: the OpenGL ES 3 renderers.

The unit tests in `core/src/test` render depth images of a synthetic scene (a sphere in front of
a wall) from several camera poses. They fuse those images, mesh the result, and check the mesh
against the true geometry: accuracy, normals, winding, colours, export round-trips and ray
picking. With 2 cm voxels and perfect synthetic depth, the median surface error is under 1 mm.
On a real phone, accuracy is limited by the depth sensor and by ARCore tracking drift. Expect
roughly 1 to 3 cm at room scale.

## Limitations and next steps

- Colour is stored **per vertex**. It looks good at 1.5 to 2 cm detail, but fine print and
  sharp textures blur. The next step would be UV-mapped photo textures.
- There is no loop closure. Over very long walks, ARCore drift can double surfaces. Scan large
  sites in sections.
- One scan's memory is capped at about 60,000 voxel blocks (~200 MB). Use the Large site level
  for big areas.
