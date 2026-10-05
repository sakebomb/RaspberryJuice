# Publishing

A `v*` tag runs [`release.yml`](../.github/workflows/release.yml). It builds the jar, cuts the
GitHub Release, and then publishes the same build to the registries below. The GitHub Release is
the source of truth, and the registries mirror it.

| Target | Package | Job runs when | Credential |
|---|---|---|---|
| GitHub Releases | plugin jar | every tag push | built-in `GITHUB_TOKEN` |
| Modrinth | plugin jar | repo variable `MODRINTH_PROJECT_ID` is set | secret `MODRINTH_TOKEN` |
| Hangar | plugin jar | repo variable `HANGAR_PROJECT` is set | secret `HANGAR_API_KEY` |
| PyPI | `raspberryjuice` (`client/`) and `raspberryjuice-mcpi` (`mcpi-client/`) | repo variable `PYPI_PUBLISH` is `true` | trusted publishing (no secret) |

A registry whose variable is unset is skipped, so the tag still produces the GitHub Release.
The two Python packages version independently. A tag that doesn't bump one of them skips the
upload for that one (`skip-existing`).

## One-time setup

### Modrinth

1. Create a plugin project named **RaspberryJuice Reloaded** with the slug
   `raspberryjuice-reloaded`. Paste the [listing text](#listing-text) and upload
   [`assets/icon.png`](assets/icon.png). License: Apache-2.0. Source:
   `https://github.com/sakebomb/RaspberryJuice`. Categories: `game-mechanics`, `utility`.
   Server side: required. Client side: unsupported.
2. Create a personal access token with the `Create versions` scope.
3. In the GitHub repo settings, add the secret `MODRINTH_TOKEN`, plus the variable
   `MODRINTH_PROJECT_ID` set to the project ID from the project page.

### Hangar

1. Create a project named **RaspberryJuice-Reloaded** (category: Admin Tools or Developer Tools).
   Paste the [listing text](#listing-text) and upload the icon. The README links to
   `https://hangar.papermc.io/sakebomb/RaspberryJuice-Reloaded`. If Hangar gives it a
   different URL, update the README.
2. Create an API key with the `create_version` permission.
3. Add the secret `HANGAR_API_KEY`, plus the variable `HANGAR_PROJECT` set to the project slug.

To test the upload by hand: `HANGAR_API_KEY=… HANGAR_PROJECT=… scripts/hangar-publish.sh
target/raspberryjuice-2.1.0.jar 2.1.0 26.2`.

### PyPI

1. On PyPI, add two pending trusted publishers. Both use owner `sakebomb`, repository
   `RaspberryJuice` and workflow `release.yml`:
   - project `raspberryjuice`, environment `pypi-raspberryjuice`
   - project `raspberryjuice-mcpi`, environment `pypi-raspberryjuice-mcpi`
2. In the GitHub repo, create the two environments with those names. If you restrict their
   deployment refs, allow both `v*` tags and the `master` branch. Tag pushes run on the tag,
   and a [backfill](#publishing-an-existing-release) runs on `master`.
3. Add the variable `PYPI_PUBLISH` = `true`.

## Publishing an existing release

To publish a tag that was released before a registry was set up (for example, v2.1.0), run
**Actions → Release → Run workflow** on `master` with that tag as input, or run
`gh workflow run release.yml --ref master -f tag=v2.1.0`. Run it from `master`, not from the
tag: the workflow and the Hangar script come from the selected ref, and older tags don't have
them. It rebuilds from the tag and publishes to every configured registry. It does not touch
the existing GitHub Release.

A registry job runs only after the jar builds, the versions in `pom.xml`, `plugin.yml` and
`client/pyproject.toml` match the tag, and the GitHub Release succeeds. A backfill skips the
GitHub Release step.

## Updating supported Minecraft versions

`MC_VERSIONS` at the top of `release.yml` lists the versions a release is tested on. Change it
when a release is verified on a new Minecraft version.

## Listing text

**Name:** RaspberryJuice Reloaded

**Summary:** Program Minecraft with Python. A maintained Paper 26.2 port of RaspberryJuice
(the Minecraft Pi API), with a turtle agent, events and classroom mode.

**Description:**

> RaspberryJuice Reloaded lets you write Python that builds in, and reacts to, a Minecraft
> world. It is a server plugin that speaks the Minecraft Pi socket API, so the classic
> Raspberry Pi and *Adventures in Minecraft* lessons run on a current Paper server.
>
> **This is a bridge, not a standalone mod.** Players write Python on their own computer with
> one of the two client libraries:
>
> - `pip install raspberryjuice`: a modern, typed client (`from raspberryjuice import Minecraft`).
> - `pip install raspberryjuice-mcpi`: a drop-in `mcpi` that runs existing Pi scripts unchanged
>   (`from mcpi.minecraft import Minecraft`).
>
> **What you can do from Python**
> - Place and read blocks, build structures, write signs, and chat.
> - Drive a turtle-style **agent** that moves, turns and builds.
> - Spawn and control mobs: pathfinding, names, health and AI.
> - Change time and weather, clone regions, and set player game mode and items.
> - React to chat, block hits, player moves, block breaks and places, and deaths.
>
> **Made for classrooms.** Optional classroom mode gives each student their own plot, build
> protection and rate caps. Teachers can `/rj freeze`, `unfreeze` and `reset` students.
> Optional auth tokens protect a shared server.
>
> **Requirements:** Paper 26.2 and Java 25. The plugin listens on `localhost:4711` by default.
> Read SECURITY.md before you open it to a network.
>
> This is a maintained fork of [zhuowei/RaspberryJuice](https://github.com/zhuowei/RaspberryJuice),
> which is no longer updated. Licensed under Apache-2.0.
> Source, docs and issues: https://github.com/sakebomb/RaspberryJuice
