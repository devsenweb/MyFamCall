# Third-party components

This repository does not grant a public license to the family application's original source. The intended GitHub destination is private unless the owner directs otherwise.

Build/runtime dependencies retain their respective licenses:
- [GetStream WebRTC Android 1.3.10](https://github.com/GetStream/webrtc-android): Apache-2.0 wrapper/distribution; native WebRTC incorporates the [WebRTC BSD license and notices](https://webrtc.googlesource.com/src/+/refs/heads/main/LICENSE). This is the raw library, not a subscription to Stream's hosted service.
- [Firebase Android SDK](https://github.com/firebase/firebase-android-sdk): Apache-2.0 for open-source SDK components; Firebase services separately have their own terms/quotas.
- [Gradle](https://github.com/gradle/gradle): Apache-2.0. The generated wrapper JAR/scripts are checked in for a reproducible entrypoint; distribution checksum is pinned.
- Android build tools/platform and Firebase emulator tools are external development tools, not vendored in this source archive.
- JUnit4 and npm test dependencies are development dependencies. See Gradle declarations and signaling/package-lock.json for the pinned dependency graph.

Before release distribution, generate a complete transitive dependency/native notices inventory and include every required license/notice. This summary is not a complete legal compliance audit or an assertion that all native third-party notices have been inventoried.
