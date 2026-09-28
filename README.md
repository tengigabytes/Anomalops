# Anomalops

[繁體中文](README.zh-TW.md)

An open-source underwater camera app for Google Pixel phones used inside touch-screen dive housings
(DiveVolk SeaTouch 4 series). It aims to give recreational divers correct underwater colour and one-handed,
glove-free operation through a gel membrane.

> **Status:** design phase. Requirements, MVP scope, architecture decisions and roadmap are written;
> development has not started. Nothing here is usable yet.

## Planned v1.0 (MVP)

- Five underwater scene presets and depth-band white balance applied in the Camera2 capture request,
  so the preview matches the saved photo
- Dive lock mode: full screen, no accidental exits, large touch targets away from the housing edges
- RAW (DNG) kept on demand: long-press the thumbnail within 10 s
- 1 Hz sensor logging for post-dive analysis
- Target device: Pixel 10 Pro; the architecture is table-driven so Pixel 6 Pro – 11 Pro can be added later

## How this project is made

- Requirements, all decisions (scope, architecture, licensing) and every review:
  **Terry Wang** ([@tengigabytes](https://github.com/tengigabytes)).
- Code is written by **Claude** (Anthropic's AI model, via Claude Code) following his instructions.
  Design documents such as ADRs, acceptance criteria and development rules were drafted by Claude from his
  decisions and finalized after his review.
- Every change is reviewed and accepted by him before it is committed. Commits containing AI-written
  content carry a `Co-Authored-By: Claude` trailer. See [docs/dev/authorship.md](docs/dev/authorship.md).

## Documentation

Project documentation is written in Traditional Chinese (Taiwan); code and comments are in English.
Start at [docs/README.md](docs/README.md).

## License

- Code: [GPL-3.0-or-later](LICENSE) with additional terms in [NOTICE.md](NOTICE.md)
- Documentation: [CC BY-SA 4.0](docs/LICENSE.md)

"Anomalops" and its logo are not licensed for use by modified versions; see [NOTICE.md](NOTICE.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
