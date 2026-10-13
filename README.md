<div align="center">

# MangaLens

**Tradutor visual de mangás em tempo real para Android**

*Traduza mangás, manhuas e webtoons diretamente sobre o leitor, sem sair da leitura — powered by Gemini AI*

![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![Gemini](https://img.shields.io/badge/Gemini%203.8%20Flash-API-8E75B2?style=flat-square&logo=google&logoColor=white)
![License](https://img.shields.io/badge/Licença-MIT-22C55E?style=flat-square)

</div>

---

## O que é

MangaLens é uma **lupa de tradução flutuante** para mangás, manhuas e webtoons. O aplicativo funciona sobre leitores Android como Mihon, Tachiyomi, Komikku e Kotatsu, captura a tela via `MediaProjection` e desenha os overlays traduzidos sobre os balões.

O app utiliza um backend próprio para proteger a `GEMINI_API_KEY`. A chave nunca é empacotada no APK.

### Como funciona

```text
Você lê no seu app favorito
    |
  | MangaLens captura a tela via MediaProjection
    v
Backend MangaLens recebe a imagem e os idiomas
    |
  | A key permanece somente no servidor
    v
Gemini 3.8 Flash analisa e traduz a imagem
  |
  v
MangaLens posiciona os overlays sobre os balões
```

O nome exato do modelo interno usado pelo Google Lens não é público. O projeto utiliza o modelo multimodal público estável `gemini-3.8-flash`.

## Funcionalidades

| Feature | Descrição |
|---|---|
| 🔍 **OCR + tradução visual** | Detecta texto em balões, narrações, efeitos sonoros e blocos verticais. |
| 🔄 **Tradução automática** | Um único botão ativa a captura e o monitoramento da tela. |
| ⏱️ **Espera inteligente** | Aguarda a tela ficar parada por aproximadamente 2 segundos antes de traduzir. |
| 🪟 **Overlay flutuante** | Funciona sobre o leitor atual sem importar o mangá. |
| 🧠 **Cache de frames** | Evita reenviar a mesma tela repetidamente. |
| 📋 **Copiar tradução** | Toque no overlay para copiar o texto traduzido. |
| 📚 **Histórico local** | Armazena traduções recentes no dispositivo. |
| 🔐 **Backend protegido** | A chave Gemini permanece fora do aplicativo Android. |

## Fluxo de ativação

Toque em **Ativar tradução automática**, conceda as permissões de sobreposição e captura de tela e selecione o idioma de destino em Configurações. Ao abrir uma página, o MangaLens limpa os overlays antigos durante a rolagem e envia a nova tela somente quando ela permanece estável.

O botão principal funciona como um interruptor: ao desativar, o serviço e todos os overlays são removidos.

## Requisitos

- JDK 17 completo.
- Android SDK 35.
- Android 7.0 / API 24 ou superior.
- Node.js 18 ou superior para o backend local.
- Celular e computador na mesma rede quando usar um aparelho físico.

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

## Testar no computador

### 1. Testar o backend

Em um terminal PowerShell:

```powershell
cd backend
Copy-Item .env.example .env
# Edite .env e informe GEMINI_API_KEY
npm test
npm start
```

Em outro terminal, confirme que o servidor está vivo:

```powershell
Invoke-RestMethod http://localhost:8080/health
```

O resultado esperado contém `ok: true`. A chamada real de tradução exige uma key válida e uma imagem; os testes automatizados usam uma resposta simulada.

Enquanto o backend estiver rodando, cada chamada produz um log semelhante a:

```text
[request-id] POST /v1/translate -> 200 (1850ms) overlays=3
```

Em caso de erro, o backend registra a stack trace com o mesmo `request-id` e devolve esse identificador ao Android. Assim, o texto exibido no app pode ser relacionado diretamente ao erro no terminal. Não são registrados a chave Gemini nem o conteúdo da imagem.

Se aparecer `EADDRINUSE ... 8080`, já existe um backend rodando. Use-o, consulte `/health` ou encerre o processo anterior antes de executar `npm start` novamente.

### 2. Testar o Android no emulador

Com o backend rodando, confirme que existe um emulador Android iniciado e conectado:

```powershell
adb devices
```

Se `adb` não for reconhecido, configure o Android SDK nesta sessão do PowerShell:

```powershell
$env:ANDROID_HOME="C:\Users\eduardo.asousa\AppData\Local\Android\Sdk"
$env:Path="$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:Path"
adb version
```

Para manter essa configuração em novas sessões, adicione `platform-tools` às variáveis de ambiente do Windows ou abra o terminal pelo Android Studio.

Gere e instale o APK apontando para o host do emulador:

```powershell
$env:JAVA_HOME="C:\Users\eduardo.asousa\.jdk\jdk-17.0.12+7"
.\gradlew.bat :app:assembleDebug -PTRANSLATION_API_BASE_URL=http://10.0.2.2:8080
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

No app:

1. Toque em **Ativar tradução automática**.
2. Conceda **Exibir sobre outros apps**.
3. Autorize a captura de tela.
4. Abra uma página de mangá com texto dentro do emulador.
5. Aguarde a captura automática e confirme os overlays traduzidos.
6. Toque em um overlay e confirme que o texto é copiado.
7. Toque novamente no botão principal e confirme que o serviço e os overlays desaparecem.

### 3. Observar logs

Para investigar falhas de conexão ou permissão:

```powershell
adb logcat -c
adb logcat | Select-String "MangaLens|GeminiTranslator|AndroidRuntime"
```

Mensagens `HTTP 400` ou `HTTP 502` normalmente indicam configuração do backend, key ou cota. Mensagens de permissão indicam que a captura ou a sobreposição ainda não foi autorizada.

### 4. Testar em celular físico

Use o IPv4 do computador na mesma rede Wi-Fi do celular e gere outro APK:

```powershell
ipconfig
$env:JAVA_HOME="C:\Users\eduardo.asousa\.jdk\jdk-17.0.12+7"
.\gradlew.bat :app:assembleDebug -PTRANSLATION_API_BASE_URL=http://192.168.1.100:8080
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Troque `192.168.1.100` pelo IPv4 correto. Libere a porta TCP 8080 no firewall do Windows se o endpoint `/health` não puder ser acessado pelo celular.

## Backend local

O backend mantém a chave Gemini fora do APK.

### Windows PowerShell

```powershell
cd backend
Copy-Item .env.example .env
# Edite backend/.env e substitua MY_GEMINI_API_KEY pela sua chave
npm test
npm start
```

Verifique o servidor em `http://localhost:8080/health`.

Crie a chave em [Google AI Studio](https://aistudio.google.com/apikey). Nunca coloque essa chave em arquivos do Android ou no APK.

### Emulador Android

O valor padrão do Android é `http://10.0.2.2:8080`. Esse endereço aponta para o computador host a partir do emulador.

### Celular físico

1. Execute `ipconfig` e copie o IPv4 do computador.
2. Inicie o backend com `npm start`.
3. Libere a porta TCP 8080 no firewall, se necessário.
4. Gere o APK apontando para o IPv4:

```powershell
$env:JAVA_HOME="C:\Users\eduardo.asousa\.jdk\jdk-17.0.12+7"
.\gradlew.bat :app:assembleDebug -PTRANSLATION_API_BASE_URL=http://192.168.1.100:8080
```

Troque `192.168.1.100` pelo endereço real do computador.

## Gerar APK para testes

### Windows PowerShell

```powershell
$env:JAVA_HOME="C:\Users\eduardo.asousa\.jdk\jdk-17.0.12+7"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug -PTRANSLATION_API_BASE_URL=http://10.0.2.2:8080
```

O arquivo será gerado em:

`app/build/outputs/apk/debug/app-debug.apk`

Para instalar em um dispositivo conectado:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Para instalar no emulador, mantenha o backend rodando em `localhost:8080`. Para um celular físico, gere novamente usando o IP da máquina.

### Android Studio

1. Abra o projeto.
2. Configure o JDK 17.
3. Execute `app > Tasks > build > assembleDebug`.
4. Instale `app-debug.apk` no dispositivo.

## Configurar outro backend

Passe a URL no Gradle:

```powershell
.\gradlew.bat :app:assembleDebug -PTRANSLATION_API_BASE_URL=https://seu-backend.example.com
```

O backend precisa expor:

- `GET /health`
- `POST /v1/translate`

O corpo de `POST /v1/translate` é:

```json
{
  "imageBase64": "...",
  "sourceLanguage": "ja",
  "targetLanguage": "pt"
}
```

A resposta esperada é:

```json
{
  "overlays": [
    {
      "originalText": "...",
      "translatedText": "...",
      "yMin": 10,
      "xMin": 20,
      "yMax": 30,
      "xMax": 80,
      "isVertical": false,
      "bubbleType": "SPEECH"
    }
  ]
}
```

## Estrutura

```text
app/                         Aplicativo Android
backend/src/server.js        Proxy HTTP para Gemini
backend/test/                Testes do backend
backend/.env.example         Configuração do backend sem segredo real
```

## CI/CD

O workflow do GitHub Actions executa os testes e gera o APK debug como artifact. A chave Gemini não é necessária para compilar o Android; ela deve ser configurada somente no ambiente onde o backend for hospedado.

## Troubleshooting

| Problema | Solução |
|---|---|
| Backend não responde | Execute `npm start` em `backend/` e verifique `/health`. |
| Emulador não conecta | Use `http://10.0.2.2:8080`. |
| Celular não conecta | Use o IPv4 do computador e libere TCP 8080 no firewall. |
| `ERRO NA API` | Confira o backend, a cota e `backend/.env`. |
| APK não compila | Confirme JDK 17 e Android SDK 35. |

## Licença

MIT. Consulte [LICENSE](LICENSE).
