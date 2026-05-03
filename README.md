![.](https://cdn.modrinth.com/data/cached_images/cbf9d31899fe7928e15d74e51a10823c287a1fff.png)

[![Github](https://cdn.modrinth.com/data/cached_images/98785f1d471df1a7aceee62cd2c715d80de8e1bd.png)](https://github.com/imsawiq/collins/releases/tag/0.1.0)
[![Discord](https://cdn.modrinth.com/data/cached_images/ce03caef41db32fe933e0a8ec6e8aca70775418d.png)](https://discord.gg/jsH9bGj2NU)
[![Telegram](https://cdn.modrinth.com/data/cached_images/226d7052633712f6e671b07b6180ec6b12d13068.png)](https://t.me/sawiqp)

Monorepo containing the **Collins** video screens system for Minecraft.

This repository includes two parts that work together:

- **Collins Fabric (client mod)** — renders and plays videos on in-game screens.
    - Modrinth: https://modrinth.com/project/collins-fabric
- **Collins Paper (server plugin)** — creates and manages screens, syncs playback state to clients.
    - Modrinth: https://modrinth.com/project/collins-paper

> To watch videos on a server you need **both**:
> - the **Paper plugin** installed on the server
> - the **Fabric mod** installed on the client

# EN

**Client-side** mod that renders and plays videos on in-game screens (Collins screens) **+** a **server-side** Paper plugin that creates and controls those screens.

> To watch videos on a server you need **both**:
> - Server: **Collins Paper** plugin — https://modrinth.com/project/collins-paper
> - Client: **Collins Fabric** mod — https://modrinth.com/project/collins-fabric

### ✨ Features

#### Client mod (Collins Fabric)
- **Video playback on screens**: render video frames directly in-world.
- **Supported sources**:
    - **YouTube** videos (regular VOD).
    - **RuTube** videos.
    - **VK Video / VK Clips** (`vk.com/video`, `vk.com/clip`, `vkvideo.ru`).
    - **YouTube live streams**.
    - **Twitch live streams** (with stitched-ad filtering).
    - Direct video URLs (`http(s)://.../*.mp4` etc.).
- **Audio playback**: client-side audio with local volume control.
- **Actionbar timeline**: optional live timeline display near active screens.
- **Config menu** (ModMenu): toggle rendering, set local volume, toggle actionbar timeline.
- **Lightweight client commands**:
    - `/collinsc time` — prints current timeline info in chat.

#### Server plugin (Collins Paper)
- **Create and manage screens**: server controls what plays on each screen.
- **Playlists**: create playlists with multiple videos, auto-advancement, and loop support.
- **Multi-platform link resolution**: YouTube / RuTube / VK Video links plus YouTube and Twitch live streams are automatically resolved by the client mod; the server just stores and broadcasts the URL.
- **Playback control commands**: play/stop/seek utilities (including backward seek).
- **Synced state broadcast**: clients receive the screen state and play it locally.

### 📦 Requirements / Dependencies

#### Client
- **Fabric API** — required.
- **ModMenu** — optional (recommended for the config screen).

#### Server
- **Paper / Spigot-compatible server**

### 🔗 Links
- Collins Fabric (client mod): https://modrinth.com/project/collins-fabric
- Collins Paper (server plugin): https://modrinth.com/project/collins-paper
- Source: https://github.com/imsawiq/collins
- Issues: https://github.com/imsawiq/collins/issues

### ⌨ Commands (permission: `collins.admin`)

#### Selection / Screen creation
- `/collins pos1`
    - Sets **pos1** to the block you are looking at.
- `/collins pos2`
    - Sets **pos2** to the block you are looking at.
    - If the selection is valid, it briefly shows a frame preview.
- `/collins create <name>`
    - Creates a new screen using your pos1/pos2 selection and saves it.

#### URL / Playback control
- `/collins seturl <screen> <url>`
    - Sets the video URL for a screen.
    - Resets playback position so a new URL never inherits an old timestamp.
- `/collins play <screen>`
    - Starts playback **from 0:00**.
- `/collins pause <screen>`
    - Stops playback but **keeps** the current position.
- `/collins resume <screen>`
    - Resumes playback from the saved position.
- `/collins stop <screen>`
    - Stops playback and **resets** position back to **0:00**.
- `/collins seek <screen> <seconds>`
    - Seeks relatively by N seconds (use negative values to go backwards).
    - Prints a message: from -> to.
- `/collins back <screen> <seconds>`
    - Convenience command: seeks backwards by N seconds (always subtracts).

#### Global settings (broadcast to clients)
- `/collins volume set <0..2>`
    - Sets **global volume multiplier** (0.0 to 2.0) for all screens.
- `/collins volume reset`
    - Resets global volume to **1.0**.
- `/collins radius set <1..512>`
    - Sets **hear radius** (how far away clients can hear/track screen audio).
- `/collins radius reset`
    - Resets hear radius to **100**.

#### Playlists
- `/collins playlist <screen> add <url>`
    - Adds a video to the screen's playlist.
- `/collins playlist <screen> list`
    - Shows all videos in the playlist.
- `/collins playlist <screen> play [index]`
    - Starts playing from the playlist (optionally from a specific index).
- `/collins playlist <screen> next` / `prev`
    - Skips to the next or previous video.
- `/collins playlist <screen> loop <true/false>`
    - Enables or disables playlist looping.
- `/collins playlist <screen> clear`
    - Clears the playlist.

#### Management
- `/collins list`
    - Lists all screens, showing URL and playing state.
- `/collins remove <screen>`
    - Removes a screen and clears its playback state.

### 👥 Authors
- **Sawiq** - Chief Coder
- **Zorsh** - Assistant Coder
- **EssciZ** - Designer

Found a bug or have an idea? Please open an issue on GitHub.

![ы](https://cdn.modrinth.com/data/cached_images/72c0ea585055e8be534afda20b6b4c8b48e7315b_0.webp)

# RU

**Клиентский** мод, который отображает и проигрывает видео на экранах в мире (экраны Collins) **+** **серверный** Paper-плагин, который создаёт и управляет этими экранами.

> Чтобы смотреть видео на сервере, **обязательно нужны обе части**:
> - Сервер: плагин **Collins Paper** — https://modrinth.com/project/collins-paper
> - Клиент: мод **Collins Fabric** — https://modrinth.com/project/collins-fabric

### ✨ Особенности

#### Клиент (Collins Fabric)
- **Проигрывание видео на экранах**: видео отображается прямо в мире.
- **Поддерживаемые источники**:
    - **YouTube** — обычные видео.
    - **RuTube** — видео.
    - **VK Видео / VK Клипы** (`vk.com/video`, `vk.com/clip`, `vkvideo.ru`).
    - **YouTube стримы** (live).
    - **Twitch стримы** (live, с фильтрацией встроенной рекламы).
    - Прямые ссылки на видео (`http(s)://.../*.mp4` и т.п.).
- **Звук**: звук проигрывается на клиенте, есть локальная регулировка громкости.
- **Таймлайн в actionbar**: опционально показывает прогресс рядом с активными экранами.
- **Меню настроек** (ModMenu): тумблер рендера, локальная громкость, таймлайн в actionbar.
- **Клиентские команды**:
    - `/collinsc time` — пишет текущий таймлайн в чат.

#### Сервер (Collins Paper)
- **Создание и управление экранами**: сервер задаёт что и где проигрывается.
- **Плейлисты**: создавай плейлисты с несколькими видео, автопереключением и зацикливанием.
- **Поддержка нескольких платформ**: ссылки YouTube / RuTube / VK Видео, а также стримы YouTube и Twitch автоматически резолвятся клиентским модом — сервер просто хранит и рассылает URL.
- **Команды управления**: play/stop/seek (включая перемотку назад).
- **Синхронизация состояния**: клиентам отправляется состояние экранов, проигрывание идёт локально.

### 📦 Требования / зависимости

#### Клиент
- **Fabric API** — обязательно.
- **ModMenu** — опционально (рекомендуется для удобного меню настроек).

#### Сервер
- **Paper / совместимый Spigot сервер**

### 🔗 Ссылки
- Collins Fabric (клиентский мод): https://modrinth.com/project/collins-fabric
- Collins Paper (серверный плагин): https://modrinth.com/project/collins-paper
- Source: https://github.com/imsawiq/collins
- Issues: https://github.com/imsawiq/collins/issues

### ⌨ Команды (permission: `collins.admin`)

> Все команды выполняются **только игроком** (не из консоли).

#### Выделение / создание экрана
- `/collins pos1`
    - Ставит **pos1** на блок, на который ты смотришь.
- `/collins pos2`
    - Ставит **pos2** на блок, на который ты смотришь.
    - Если выделение валидное — кратко показывает рамку-превью.
- `/collins create <name>`
    - Создаёт новый экран по выделению pos1/pos2 и сохраняет его.

#### URL / управление воспроизведением
- `/collins seturl <screen> <url>`
    - Устанавливает ссылку на видео для экрана.
    - Сбрасывает позицию, чтобы новый URL не наследовал таймер от прошлого видео.
- `/collins play <screen>`
    - Запускает воспроизведение **с 0:00**.
- `/collins pause <screen>`
    - Останавливает воспроизведение, но **сохраняет** текущую позицию.
- `/collins resume <screen>`
    - Продолжает воспроизведение с сохранённой позиции.
- `/collins stop <screen>`
    - Останавливает воспроизведение и **сбрасывает** позицию на **0:00**.
- `/collins seek <screen> <seconds>`
    - Перемотка относительно текущей позиции на N секунд (можно отрицательные значения).
    - Пишет сообщение: от -> до.
- `/collins back <screen> <seconds>`
    - Удобная команда: перемотка **назад** на N секунд.

#### Глобальные настройки (рассылаются клиентам)
- `/collins volume set <0..2>`
    - Задаёт **глобальный множитель громкости** (0.0..2.0) для всех экранов.
- `/collins volume reset`
    - Сброс глобальной громкости на **1.0**.
- `/collins radius set <1..512>`
    - Задаёт **радиус слышимости** (на каком расстоянии клиент слышит/отслеживает звук).
- `/collins radius reset`
    - Сброс радиуса слышимости на **100**.

#### Плейлисты
- `/collins playlist <screen> add <url>`
    - Добавляет видео в плейлист экрана.
- `/collins playlist <screen> list`
    - Показывает все видео в плейлисте.
- `/collins playlist <screen> play [index]`
    - Запускает воспроизведение из плейлиста (можно указать индекс).
- `/collins playlist <screen> next` / `prev`
    - Переключает на следующее или предыдущее видео.
- `/collins playlist <screen> loop <true/false>`
    - Включает или выключает зацикливание плейлиста.
- `/collins playlist <screen> clear`
    - Очищает плейлист.

#### Управление
- `/collins list`
    - Показывает список всех экранов, URL и состояние (играет/нет).
- `/collins remove <screen>`
    - Удаляет экран и очищает его состояние воспроизведения.

### 👥 Авторы
- **Sawiq** - Главный кодер
- **Zorsh** - Помощник кодера
- **EssciZ** - Дизайнер

Нашёл баг или есть идея? Открой issue на GitHub.

![.](https://cdn.modrinth.com/data/cached_images/47eae40180fb78cffd6a67ad84dfea745d4d598f_0.webp)

## Projects

### `collins-fabric`
Client-side Fabric mod.

- Renders video frames directly in-world on Collins screens.
- Plays audio client-side.
- Has a client config (ModMenu) for local volume / rendering toggle / actionbar timeline.

### `collins-paper`
Server-side Paper plugin.

- Screen selection and creation commands.
- Playback control (play/pause/resume/stop/seek/back).
- Syncs screens and playback state to clients.

## Requirements

- **Java 21**
- **Gradle** (use the included wrapper)

## Build

From the repository root:

```bash
./gradlew :collins-fabric:build
./gradlew :collins-paper:build
```

Artifacts:

- Fabric mod jar:
    - `collins-fabric/build/libs/`
- Paper plugin jar:
    - `collins-paper/build/libs/`

## Quick start (server + client)

1) **Server (Paper)**

- Put the built jar into:
    - `plugins/`
- Start the server.

2) **Client (Fabric)**

- Install Fabric Loader and Fabric API.
- Put the built jar into:
    - `.minecraft/mods/`

3) **Use commands (server admin)**

All plugin commands require permission: `collins.admin`.

- Select screen corners:
    - `/collins pos1`
    - `/collins pos2`
- Create a screen:
    - `/collins create <name>`
- Set video URL:
    - `/collins seturl <screen> <url>`
- Start playback:
    - `/collins play <screen>`

Client helper command:

- `/collinsc time` — prints current timeline to chat.

## Authors

- Sawiq
- Zorsh
- EssciZ
