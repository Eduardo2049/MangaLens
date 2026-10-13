# MangaLens

Tradutor visual de mangás para Android. O app captura a tela com `MediaProjection`, envia novos frames ao backend MangaLens e desenha a tradução sobre o leitor atual.

## Arquitetura

```text
Leitor Android
    |
    | captura de tela + idioma de origem/destino
    v
Backend MangaLens (/v1/translate)
    |
    | GEMINI_API_KEY somente no servidor
    v
Gemini 3.8 Flash
```

O nome exato do modelo interno usado pelo Google Lens não é público. O backend usa o modelo multimodal público estável `gemini-3.8-flash`.

## Fluxo do aplicativo

1. Toque em **Ativar tradução automática**.
2. Conceda as permissões de sobreposição e captura de tela.
3. Escolha o idioma de destino em Configurações.
4. Abra Mihon, Tachiyomi, Komikku, Kotatsu ou outro leitor.
5. O serviço detecta mudanças na tela e traduz automaticamente.
6. Toque em um overlay para copiar o texto traduzido.

O botão principal funciona como um interruptor: ao desativar, o serviço e os overlays são removidos.

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
