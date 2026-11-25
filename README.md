# 📺 Minhas TV — Player IPTV M3U (Android TV Box & Celular)

Aplicativo Android nativo desenvolvido em **Kotlin + Jetpack Media3 (ExoPlayer)** para rodar listas **M3U / M3U8** de servidores IPTV com fluidez, decodificação por hardware e sem travamentos em **TV Box (Android 5.0+)** e **celulares**.

---

## ✨ Recursos Principais

- **Player Anti-Travamento (Media3 / ExoPlayer):**
  - Decodificação por hardware (`MediaCodec`) com fallback automático para software em TV Boxes antigas.
  - Buffer ajustado (`DefaultLoadControl` 15s–50s, início rápido em 2,5s) e `OkHttp` com reconexão automática (até 5 tentativas) se o sinal oscilar.
  - Suporte a `User-Agent`, `Referer`, `#EXTVLCOPT` e parâmetros `|User-Agent=...` na URL.
  - Troca de proporção de tela (**Ajustar / Esticar / Zoom**) direto no player.
- **Interface Dupla (TV Box + Celular):**
  - **TV Box / Android TV:** Navegação completa por **controle remoto (D-Pad)** com destaque visual de foco, zap de canais com **Esquerda/Direita** ou **Canal +/-**, e ícone na tela inicial (`LEANBACK_LAUNCHER`).
  - **Celular / Tablet:** Layout responsivo (2 colunas em paisagem, barra horizontal em retrato) com controle por toque.
- **Gerenciamento de Listas M3U:**
  - Múltiplas listas via **URL** ou arquivo **`.m3u` local**.
  - Parser em streaming (`BufferedReader`) leve para listas grandes (10.000+ canais) sem travar TV Boxes com pouca RAM.
  - Categorias automáticas (`group-title`), **Favoritos**, **Recentes** e busca em tempo real.

---

## 📦 Como gerar o APK pelo GitHub Actions

1. Envie o código para o GitHub (`git push -u origin main`).
2. No repositório do GitHub, clique na aba **Actions**.
3. Selecione o workflow **Build Android APK** (ele roda automaticamente a cada `push`).
4. Ao concluir (cerca de 2–3 minutos), baixe o arquivo **`MinhasTV-IPTV-debug-apk`** na seção **Artifacts**, descompacte e instale o `app-debug.apk` no seu celular ou TV Box!
