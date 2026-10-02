# 4Link

4Link is a small interface through which Android apps ask each other to do
things on the user's behalf, in the pattern of MCP (the Model Context Protocol).

- Each member app exports a ContentProvider at `<package>.4link` with three calls: hello, catalogue, invoke.
- The catalogue lists functions: a name, a description written for an AI model, typed inputs as JSON Schema, and whether the function reads, changes or deletes.
- Callers are identified by their signing certificate, never by a name they claim; apps signed with our own keys are family, any other app is paired by the user first.
- Anything that changes or deletes asks the user to confirm; text from other apps reaches a model only as marked data, never as instructions.
- The library is pure Kotlin at its core, with thin Android adapters, and contains no UI and no accessibility code (a test fails the build if either appears).

## Including it

As a git submodule in your app's repository, then in your `settings.gradle`:

    git submodule add https://github.com/mr-bizzy/4link.git 4link

    include ':fourlink'
    project(':fourlink').projectDir = new File(settingsDir, '4link/library')

and `implementation project(':fourlink')` in your app module. The family
certificate digests are a resource (`fourlink_family_digests`) that your build
types override.

## Specification

[docs/4LINK-SPEC.md](docs/4LINK-SPEC.md)

## Licence

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
