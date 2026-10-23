<div align="center">

# MangaLens

**Tradutor visual de mangás em tempo real para Android**

*Traduza mangás, manhwas e webtoons diretamente sobre o leitor, sem sair da leitura — OCR no aparelho + IA gratuita via OpenRouter*

![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![OpenRouter](https://img.shields.io/badge/OpenRouter-modelos%20free-6566F1?style=flat-square)
![License](https://img.shields.io/badge/Licença-MIT-22C55E?style=flat-square)

</div>

---

## O que é

MangaLens é uma **lupa de tradução flutuante** para mangás, manhwas e webtoons. Funciona sobre leitores Android como Mihon, Tachiyomi, Komikku e Kotatsu: captura a tela via `MediaProjection` e desenha as traduções por cima dos balões.

### Como funciona

```text
Você lê no seu app favorito
   |
   | 3x por segundo: miniatura 36x64 da tela (sem rede, sem custo)
   v
A tela parou em conteúdo novo?  -- não --> não faz nada
   | sim
   v
OCR no aparelho (ML Kit) encontra os textos e a posição exata de cada balão
   |
   | balões já traduzidos vêm do cache (sem custo)
   v
Só os textos NOVOS vão para a OpenRouter, em uma única requisição de texto
   |
   v
Traduções desenhadas sobre os balões, com fonte ajustada ao tamanho de cada um
```

Para mangá japonês **vertical** (que o OCR lê mal) ou quando você escolhe o motor "IA lê a imagem", a página é enviada como imagem para um modelo de visão; as caixas devolvidas pela IA são ajustadas às posições exatas do OCR.

### Por que é econômico

| Situação | Custo |
|---|---|
| Tela parada (você lendo) | Zero — nenhum frame novo, nenhum processamento |
| Rolando a página | Zero — só compara miniaturas |
| Página sem texto | Zero — o OCR local não acha texto, nada é enviado |
| Balão que já foi traduzido (rolar webtoon, voltar página) | Zero — cache |
| Balões novos | 1 requisição **só de texto** por tela |

A versão anterior reenviava a tela inteira como imagem a cada ~5 s, porque os próprios overlays e a bolha flutuante apareciam na captura e faziam a tela parecer "diferente". Isso foi eliminado: as regiões desenhadas pelo MangaLens são ignoradas na detecção de mudança.

## Funcionalidades

| Feature | Descrição |
|---|---|
| 🔍 **OCR no aparelho** | ML Kit (latim, japonês, coreano, chinês) — posições exatas, sem rede. |
| 🌐 **Tradução contextual** | Os balões da página vão juntos, com as últimas falas como contexto (nomes, gênero, tom). |
| 🆓 **OpenRouter grátis** | Usa apenas modelos `:free`, com fallback automático entre eles. |
| ⏱️ **Espera inteligente** | Traduz quando a tela fica parada ~1 s em conteúdo novo. |
| 🧠 **Cache** | Por balão (OCR mode) e por página (modo visão). |
| 🚦 **Limites respeitados** | Espaça requisições (20/min), conta o uso diário e respeita o `Retry-After` de um 429. |
| 🪟 **Overlay que não atrapalha** | Uma única camada que ignora toques: o leitor continua rolando normalmente. |
| 🔐 **Backend opcional** | Proxy que guarda a chave fora do APK e só aceita modelos gratuitos. |

## Usando a bolha flutuante

| Gesto | Ação |
|---|---|
| Arrastar | Move a bolha |
| Tocar | Liga/pausa a tradução automática (no modo Manual: traduz a tela / esconde a tradução) |
| Segurar ~0,5 s | Força uma nova tradução da tela atual |
| ✖ | Encerra o tradutor |

Estados: `AUTO ●` (ativo), `AGUARDANDO...` (tela em movimento), `LENDO...` (OCR), `TRADUZINDO...` (API), `LIMITE ⏳` (limite da API — tenta de novo sozinho), `ERRO ●` (segure para tentar de novo). A notificação mostra quantas requisições foram usadas hoje.

Em **Configurações** escolha o modo (Automático/Manual) e o motor (OCR + texto, ou IA lê a imagem).

## Configurar a OpenRouter (teste pessoal)

1. Crie uma conta e uma chave em [openrouter.ai/settings/keys](https://openrouter.ai/settings/keys).
2. Adicione ao `local.properties` (já ignorado pelo Git — a chave **nunca** vai para o repositório):

```properties
OPENROUTER_API_KEY=sk-or-v1-...
```

3. Gere e instale o APK (abaixo). Com a chave definida o app chama a OpenRouter direto — não precisa de backend nem de PC ligado.

Opcional no mesmo arquivo:

```properties
# Modelos em ordem de preferência (todos gratuitos). A lista de modelos free muda com o tempo:
# https://openrouter.ai/models?max_price=0
OPENROUTER_TEXT_MODELS=google/gemma-4-31b-it:free,nvidia/nemotron-3-ultra-550b-a55b:free,openrouter/free
OPENROUTER_VISION_MODELS=nvidia/nemotron-3.5-super-vl:free,google/gemma-4-31b-it:free,openrouter/free
# Limite diário que o app impõe a si mesmo (free: 50/dia; 1000/dia após comprar 10 créditos uma vez)
OPENROUTER_DAILY_LIMIT=50
```

> **Limites do plano gratuito:** 20 requisições/minuto e **50 por dia** sem créditos (1000/dia depois de comprar US$ 10 em créditos uma única vez). Graças ao cache, uma leitura típica gasta 1 requisição por tela com falas novas. Quando o limite diário acaba, os balões já traduzidos continuam aparecendo.

> A chave embutida no APK só serve para uso pessoal: qualquer pessoa com o APK consegue extraí-la. Para distribuir, use o backend.

## Requisitos

- JDK 17 completo.
- Android SDK 35.
- Android 7.0 / API 24 ou superior.
- Node.js 18+ apenas se for usar o backend.

## Corrigir o JDK no VS Code

O Gradle 9.3.1 e o Android Gradle Plugin deste projeto exigem Java 17 ou superior. Se o console informar que está usando JVM 8, feche todas as janelas do VS Code e abra uma nova a partir do PowerShell com o JDK correto:

```powershell
$env:JAVA_HOME="C:\Users\eduardo.asousa\.jdk\jdk-17.0.12+7"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
code .
```

Confirme no terminal integrado:

```powershell
java -version
.\gradlew.bat --version
```

Os dois comandos devem mostrar Java 17. Depois execute `Developer: Reload Window` no VS Code. Se o aviso "build file has been changed" continuar, execute `Java: Clean Java Language Server Workspace` e reabra o projeto.

O arquivo `.vscode/settings.json` usa `${env:JAVA_HOME}` para que o projeto não dependa de um caminho absoluto de outra máquina.

## Gerar e instalar o APK

```powershell
$env:JAVA_HOME="C:\Users\eduardo.asousa\.jdk\jdk-17.0.12+7"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Ou, com o celular conectado: `.\gradlew.bat installDebug`.

No app:

1. Toque em **Ativar tradução automática**.
2. Conceda **Exibir sobre outros apps** e autorize a captura de tela (escolha "Tela inteira").
3. Abra o leitor de mangá. Ao parar numa página, a bolha mostra `LENDO...` → `TRADUZINDO...` → `AUTO ●` e as traduções aparecem.
4. Role a página: as traduções somem durante a rolagem e voltam ao parar (balões já vistos voltam instantaneamente, sem custo).

Para testar sem outro app: na tela inicial, importe uma imagem de mangá — ela passa pelo mesmo pipeline.

### Observar logs

```powershell
adb logcat -c
adb logcat | Select-String "MangaLens|AndroidRuntime"
```

Cada requisição registra `model=... tokens=...`, e cada página `Página: N traduções, requests=0|1, cache=true|false`.

## Backend (opcional)

Sem `OPENROUTER_API_KEY` no `local.properties`, o app usa o backend em `TRANSLATION_API_BASE_URL` (padrão `http://10.0.2.2:8080`, o host visto pelo emulador). O backend é um proxy fino para a OpenRouter: injeta a chave e **recusa modelos pagos**.

```powershell
cd backend
Copy-Item .env.example .env
# Edite backend/.env e informe OPENROUTER_API_KEY
npm test
npm start
```

Verifique em `http://localhost:8080/health`. Para celular físico, use o IPv4 do computador (mesma rede Wi-Fi) e libere a porta TCP 8080 no firewall:

```powershell
.\gradlew.bat :app:assembleDebug -PTRANSLATION_API_BASE_URL=http://192.168.1.100:8080
```

O build debug permite HTTP sem TLS para esse backend local; o release não.

Endpoints:

- `GET /health`
- `POST /v1/chat/completions` — corpo no formato OpenAI/OpenRouter (`models`, `messages`, ...); somente modelos `:free` ou `openrouter/free`.

## Estrutura

```text
app/src/main/java/com/example/
  service/MangaOverlayService.kt      Bolha flutuante + loop de detecção de mudança
  service/ScreenCaptureManager.kt     MediaProjection; frames convertidos só sob demanda
  service/TranslationOverlayLayer.kt  Camada única de traduções (não intercepta toques)
  translation/                        OCR, cache, prompts, orçamento de requisições, pipeline
  data/remote/OpenRouterClient.kt     Cliente OpenRouter (fallback de modelos, 429)
backend/src/server.js                 Proxy OpenRouter (opcional)
```

## Troubleshooting

| Problema | Solução |
|---|---|
| `Configure OPENROUTER_API_KEY` | Adicione a chave ao `local.properties` e gere o APK de novo. |
| `LIMITE ⏳` | Limite por minuto/dia da OpenRouter. Aguarde; o app tenta sozinho. |
| `ERRO ●` com "OpenRouter: ..." | Modelo indisponível: troque `OPENROUTER_TEXT_MODELS` por modelos free atuais. |
| Nada é traduzido | Confira se a captura é "Tela inteira" e se o idioma de origem está certo (ou "Detectar"). |
| Mangá japonês vertical mal traduzido | Use o motor "IA lê a imagem" nas Configurações. |
| Backend não conecta | `npm start` em `backend/`, `/health`, IPv4 correto e firewall. |
| APK não compila | Confirme JDK 17 e Android SDK 35. |

## Licença

MIT. Consulte [LICENSE](LICENSE).
