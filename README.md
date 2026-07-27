<div align="center">

# 🔍 MangaLens

**Tradutor visual de mangá em tempo real para Android**

*Leia qualquer mangá em qualquer leitor, com tradução sobreposta direto na tela — powered by Gemini AI*

![MangaLens](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Latest-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![Gemini](https://img.shields.io/badge/Gemini%202.5%20Flash-API-8E75B2?style=flat-square&logo=google&logoColor=white)
![License](https://img.shields.io/badge/Licença-MIT-22C55E?style=flat-square)

</div>

---

## O que é

MangaLens é um aplicativo Android que funciona como uma **lupa de tradução flutuante**, similar ao Google Lens, mas especializado em mangás, manhwas e webtoons. Ele se sobrepõe a qualquer leitor (Tachiyomi, Mihon, Komikku, Kotatsu, etc.) e traduz o texto dos balões diretamente na tela, sem tirar você do contexto.

### Como funciona

```
Você lê no app leitor favorito
          ↓
MangaLens captura a tela via MediaProjection
          ↓
Screenshot é enviado para o Gemini 2.5 Flash
          ↓
Gemini retorna texto original + tradução + posição dos balões (bounding boxes)
          ↓
MangaLens desenha cards brancos sobrepostos exatamente sobre cada balão
          ↓
Toque em qualquer card para copiar o texto traduzido
```

---

## Funcionalidades

| Feature | Descrição |
|---|---|
| 🔍 **OCR + Tradução** | Detecta e traduz texto em japonês, coreano, chinês e inglês usando Gemini Vision |
| 🪟 **Overlay flutuante** | Funciona sobre qualquer app de leitura — não precisa importar o mangá |
| ⚡ **Modo Manual** | Traduz a tela atual com um único toque no botão flutuante |
| 🔄 **Modo Dinâmico** | Monitora mudanças na tela automaticamente e traduz ao trocar de página |
| 🧠 **Cache inteligente** | Evita chamadas redundantes à API comparando hash do frame antes de traduzir |
| 📋 **Copiar texto** | Toque em qualquer overlay para copiar a tradução para a área de transferência |
| 📚 **Histórico** | Armazena as últimas traduções localmente via Room |
| ⚙️ **Configurável** | Idioma de origem, idioma alvo, opacidade, tamanho da fonte e estilo dos overlays |

---

## Modos de Tradução

### Manual (`TRADUZIR TELA`)
Abra seu leitor, navegue até a página desejada e toque no botão flutuante. MangaLens captura a tela atual, envia para o Gemini e posiciona os overlays sobre os balões. Toque novamente em `OCULTAR TRADUÇÃO` para esconder.

### Dinâmico (`AUTO ●`)
Ative nas preferências `Modo → Dinâmico`. O botão muda para `AUTO ●`. MangaLens verifica a tela a cada 2 segundos — se a página mudou (detectado via hash MD5), a tradução dispara automaticamente. Toque novamente para pausar (`AUTO ○`).

---

## Requisitos

- Android **8.0 (API 26)** ou superior
- Permissão de **sobreposição de janelas** (`SYSTEM_ALERT_WINDOW`)
- Permissão de **captura de tela** (solicitada ao iniciar)
- Chave de API do **Google Gemini** (gratuita no Google AI Studio)

---

## Instalação

### Opção A — Compilar do código-fonte

**Pré-requisitos:** Android Studio Iguana+, JDK 11+, Android SDK 36

```bash
# 1. Clone o repositório
git clone https://github.com/Eduardo2049/MangaLens.git
cd MangaLens

# 2. Configure sua chave Gemini (veja a seção abaixo)
echo "GEMINI_API_KEY=SUA_CHAVE_AQUI" > .env

# 3. Compile e instale via Android Studio
#    ou via linha de comando:
./gradlew installDebug
```

### Opção B — Instalar APK diretamente

Baixe a versão desejada na seção [Releases](https://github.com/Eduardo2049/MangaLens/releases):

| Variante | Arquivo | Quando usar |
|---|---|---|
| **Debug** | `MangaLens-debug.apk` | Desenvolvimento e testes — logs habilitados, sem ofuscação |
| **Release** | `MangaLens-release.apk` | Uso diário — otimizado, sem logs de depuração |

> **⚠️ Atenção:** Para instalar APKs de fontes externas, ative **Fontes desconhecidas** nas configurações do Android:
> `Configurações → Aplicativos → Instalar apps de fontes desconhecidas`

**Instalar via ADB (cabo USB):**
```bash
# Conecte o dispositivo com depuração USB ativada, então:
adb install MangaLens-debug.apk

# Para substituir uma versão já instalada:
adb install -r MangaLens-debug.apk
```

---

## Configuração da chave Gemini

O app usa a API Gemini para OCR e tradução. A chave é **gratuita** com cota generosa para uso pessoal.

### 1. Obter a chave

Acesse [aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey) → **Create API key** → copie a chave (começa com `AIza...`).

### 2. Adicionar ao projeto

Crie o arquivo `.env` na **raiz do projeto** (mesmo nível de `settings.gradle.kts`):

```properties
GEMINI_API_KEY=AIzaSyXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
```

> O `.env` já está no `.gitignore`. Nunca commite sua chave real. O arquivo `.env.example` com o placeholder é seguro para o repositório.

### 3. Restringir a chave (recomendado)

Para que a chave só funcione originada do seu APK assinado:

1. Acesse [console.cloud.google.com/apis/credentials](https://console.cloud.google.com/apis/credentials)
2. Clique na sua chave → **Edit API key** → **Application restrictions** → **Android apps**
3. Adicione o item:
   - **Package name:** `com.aistudio.mangalens.trvis`
   - **SHA-1 certificate fingerprint:** execute `./gradlew signingReport` e copie o SHA-1
4. Salve

---

## Como usar

1. **Abra o MangaLens** e conceda as permissões solicitadas (sobreposição e captura de tela)
2. **Inicie o serviço** tocando em "Iniciar Overlay" — um botão flutuante aparece na tela
3. **Abra seu leitor** preferido (Tachiyomi, Mihon, Komikku, etc.)
4. Navegue até uma página com texto
5. **Toque no botão flutuante** para traduzir (modo Manual) ou deixe o app detectar automaticamente (modo Dinâmico)
6. Toque em qualquer overlay para **copiar** a tradução

---

## Estrutura do projeto

```
MangaLens/
├── app/src/main/java/com/example/
│   ├── data/
│   │   ├── local/          # Room DB (histórico) + SharedPreferences (configurações)
│   │   ├── model/          # Modelos de dados: TextOverlay, BoundingBox, SupportedLanguage
│   │   ├── remote/         # GeminiVisualTranslator — cliente REST da API Gemini
│   │   └── samples/        # Dados de exemplo para o leitor interno de demonstração
│   ├── service/
│   │   └── MangaOverlayService.kt  # Serviço foreground: captura de tela + overlay
│   ├── ui/
│   │   ├── reader/         # Leitor interno com ViewModel e Compose UI
│   │   ├── components/     # Componentes reutilizáveis de UI
│   │   └── theme/          # Tema dark mode e paleta de cores
│   └── MainActivity.kt
└── .env.example            # Template de configuração da chave de API
```

---

## Tecnologias

| Camada | Tecnologia |
|---|---|
| Linguagem | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 |
| Overlay | `WindowManager` + `MediaProjection` |
| AI / OCR | Gemini 2.5 Flash (via REST) |
| Banco de dados | Room |
| Preferências | SharedPreferences |
| Imagens | Coil |
| HTTP | OkHttp 4 |
| Async | Kotlin Coroutines |
| Segredos de build | Secrets Gradle Plugin |

---

## Idiomas suportados

| Idioma de origem | Suportado |
|---|---|
| 🌐 Detectar automaticamente | ✅ |
| 🇯🇵 Japonês (vertical e horizontal) | ✅ |
| 🇰🇷 Coreano | ✅ |
| 🇨🇳 Chinês simplificado / tradicional | ✅ |
| 🇺🇸 Inglês | ✅ |

| Idioma de destino | Suportado |
|---|---|
| 🇧🇷 Português (Brasil) | ✅ |
| 🇺🇸 Inglês | ✅ |
| 🇪🇸 Espanhol | ✅ |
| 🇯🇵 Japonês | ✅ |
| 🇰🇷 Coreano | ✅ |
| 🇨🇳 Chinês | ✅ |

---

## Licença

MIT — veja [LICENSE](LICENSE) para mais detalhes.

---

<div align="center">
Feito com ☕ e muito mangá
</div>
