# 📺 NovaTV IPTV

Reprodutor de IPTV de alta performance para **Android TV Box** e **Smartphones**, desenvolvido em **Kotlin + Jetpack Media3 (ExoPlayer) + Room SQLite + Paging 3**.

## 🏗️ Arquitetura Modular

```text
app/src/main/java/com/willianswill/novatv/
├── core/
│   ├── network/        # OkHttp, Xtream Codes API, Stalker Portal (base)
│   ├── database/       # Room SQLite (Entidades, DAOs indexados, Paging 3)
│   ├── parser/         # Parser M3U em streaming (chunks) e XMLTV (EPG)
│   └── player/         # Motor Media3/ExoPlayer, Buffer, PiP, Multi-View
└── feature/
    ├── playlists/      # Gerenciamento Multi-Perfil (Xtream, M3U, Stalker)
    ├── home/           # Dashboard Home (Continuar Assistindo, Favoritos)
    ├── livetv/         # TV ao Vivo + Grade EPG + Mini-Player
    ├── vod/            # Catálogo de Filmes e Séries
    └── settings/       # Configurações, Controle Parental (PIN), Backup
```
