# WeChat Group Daily Bot

Internal Android-agent prototype for collecting text visible to a project-owned Bot account and producing a daily report for its configured recipient.

## Status

This repository is an engineering prototype, not a customer-ready personal-WeChat product.

- Redmi K80 testing found HyperOS protects MediaProjection frames of the WeChat screen; screenshot OCR is currently blocked and must not be bypassed.
- Local Room storage exists as a prototype.
- Group-only classification, reliable message parsing/deduplication, backend sync, daily reports, and Bot private-chat delivery are not complete.
- Customer deployment remains gated on a platform-authorization decision.

## Documentation

The current product logic, data model, diagnostic requirements, and implementation gates are in [the solution design](plans/personal-wechat-daily-bot-plan.md).

## Build

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home \
  bash gradlew --no-daemon :bot:assembleDebug --console=plain
```

The debug APK is produced at `bot/build/outputs/apk/debug/bot-debug.apk`.

## License

See [LICENSE](LICENSE) and [LICENSE-GPL-V2](LICENSE-GPL-V2).
