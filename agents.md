# MangaLens - AI Agent Guidelines

Este documento estabelece as regras, padrões e contexto técnico do projeto MangaLens para orientar futuros agentes de IA e manter a consistência do desenvolvimento.

---

## 🚀 Contexto do Projeto
**MangaLens** é um aplicativo Android para tradução de mangás em tempo real usando sobreposição (overlay). O app captura a tela com `MediaProjection`, faz OCR no aparelho (ML Kit) e envia apenas os textos novos para tradução via OpenRouter (modelos gratuitos).

---

## 🛠️ Tech Stack
- **Linguagem:** Kotlin (Target JVM 17)
- **UI:** Jetpack Compose (Material 3)
- **Arquitetura:** MVVM + Clean Architecture
- **Banco de Dados:** Room
- **Rede:** Retrofit + OkHttp + Moshi
- **IA:** OpenRouter (modelos `:free`) + OCR no aparelho (ML Kit Text Recognition)
- **Testes:** JUnit, Robolectric, Roborazzi (Screenshot Testing)

---

## 🏗️ Estrutura de Build Testada e Confirmada

| Componente | Versão Requerida | Observações |
| :--- | :--- | :--- |
| **Gradle** | 9.3.1 | Wrapper configurado em `gradle/wrapper/gradle-wrapper.properties` |
| **AGP** | 9.1.1 | Android Gradle Plugin (`com.android.application`) |
| **JDK** | OpenJDK 17 (Full JDK com `jlink.exe`) | Eclipse Temurin 17 ou Microsoft OpenJDK 17 em `C:\Users\eduardo.asousa\.jdk\jdk-17.0.12+7` |
| **SDK** | compileSdk 35, targetSdk 35, minSdk 24 | Configurado em `app/build.gradle.kts` |
| **core-ktx** | 1.15.0 | Versão compatível com `compileSdk 35` (em `gradle/libs.versions.toml`) |

---

## ⚙️ Regras de Build e Ambiente (Importante)

1. **Configuração do JDK 17 (`JAVA_HOME`):**
   - O Gradle requer um **JDK 17 completo (com `jlink.exe`)**. Não utilize o JDK 8 do sistema nem JBRs parciais/incompletos.
   - No terminal (PowerShell), defina sempre:
     ```powershell
     $env:JAVA_HOME="C:\Users\eduardo.asousa\.jdk\jdk-17.0.12+7"
     ```

2. **Localização do Android SDK (`local.properties`):**
   - O arquivo `local.properties` (ignorado pelo Git) deve conter o caminho válido do SDK:
     ```properties
     sdk.dir=C:/Users/eduardo.asousa/AppData/Local/Android/Sdk
     ```
   - Para teste pessoal, a chave da OpenRouter também fica aqui (nunca em arquivos rastreados):
     ```properties
     OPENROUTER_API_KEY=sk-or-v1-...
     ```

3. **Toolchain Kotlin no `app/build.gradle.kts`:**
   - Mantenha `// kotlin { jvmToolchain(17) }` comentado para que o Gradle utilize a JVM 17 informada no `JAVA_HOME` sem tentar transformações adicionais de toolchain.

4. **Nome do APK Gerado:**
   - O `app/build.gradle.kts` possui regra automática (`applicationVariants`) que gera o APK nomeado como:
     `app/build/outputs/apk/debug/MangaLens-v1.0-debug.apk`

5. **Plugins de Resolução:**
   - O plugin `foojay-resolver-convention` deve permanecer comentado em `settings.gradle.kts`.

6. **Assinatura e Portabilidade:**
   - Utilize a assinatura padrão de debug do Android.
   - Nunca commite caminhos absolutos locais em arquivos rastreados pelo Git (`gradle.properties`, etc).

---

## 💻 Comandos Úteis para Agentes e Desenvolvedores

### 1. Gerar o APK Debug:
```powershell
$env:JAVA_HOME="C:\Users\eduardo.asousa\.jdk\jdk-17.0.12+7"
.\gradlew.bat assembleDebug
```

### 2. Instalar Direto no Celular (Requer celular conectado via USB com Depuração USB ativada):
```powershell
$env:JAVA_HOME="C:\Users\eduardo.asousa\.jdk\jdk-17.0.12+7"
.\gradlew.bat installDebug
```

---

## 🎨 Padrões de Código

- **Composables:** Devem seguir o padrão de nomes `CamelCase`. Use `modifier: Modifier = Modifier` como primeiro parâmetro opcional.
- **ViewModels:** Devem usar `StateFlow` para expor estados reativos à UI.
- **DI:** Injeção manual / simplificada nos serviços e repositórios.

---

## 💸 Regras de Consumo de API

- Nunca dispare requisições em loop: a detecção de mudança (`FrameThumbnail`) deve ignorar as regiões desenhadas pelo próprio app (bolha, cards, status bar).
- Prefira OCR local + tradução de texto; o modo visão (imagem) só quando necessário.
- Toda requisição passa pelo `RequestBudget` (20/min, limite diário) e pelos caches (`TextTranslationCache`, `PageCache`).
- O dia do `RequestBudget` é o dia **UTC** (quando a OpenRouter zera a cota). O limite diário vem das Configurações (`dailyRequestLimit`: 50 grátis / 1000 com créditos).
- O cache aproximado só aceita ruído de OCR (caractere perdido, `l`↔`1`, `o`↔`0`...); troca real de letra ("ele"/"ela") é outra frase e nunca reaproveita tradução.

---

## 🔤 Tradução e Ajuste nos Balões

- `BlockTranslator` valida a resposta **por id** — inclusive os parciais do streaming, que passam pelo mesmo `normalizeIds`: ids desconhecidos/0-based, eco do original (CJK num alvo latino) e textos gigantes são rejeitados. Sem traduções válidas → 1 nova tentativa só com os faltantes e modelos rotacionados (`MAX_ATTEMPTS = 2`). Nunca trate "HTTP 200" como sucesso.
- O log `Página: ocr= cacheHits= solicitados= recebidos= válidos= exibidos=` é o diagnóstico oficial; não registre o texto dos balões.
- `BubbleTextFitter` (Kotlin puro) escolhe card + fonte: busca binária do maior tamanho que cabe (altura, largura e sem partir palavras), crescendo o card em passos sem cobrir outros balões; overflow é explícito (card mais alto), nunca truncamento.
- `TranslationOverlayLayer` desenha exatamente o `StaticLayout` medido (`StaticLayoutMeasurer`), nunca um `TextView` que re-quebre o texto.

## 🚢 CI / Release

- `.github/workflows/build.yml`: testes + APK em push/PR; tag `v*` gera GitHub Release (`versionName` = tag via `-PVERSION_NAME`).
- Nenhuma chave em builds de CI: o usuário cola a chave da OpenRouter em Configurações (`manga_lens_secrets`, excluído do backup).
- O projeto não tem backend em produção: o padrão é cada usuário colar a própria chave. Tudo que for de backend fica na pasta `back/` (proxy Node.js opcional).
- `TRANSLATION_API_BASE_URL` não tem valor padrão: nunca volte a usar `10.0.2.2` como padrão (só existe no emulador). Backend público deve exigir `MANGALENS_APP_TOKEN` (app envia `X-MangaLens-Token` = `TRANSLATION_API_TOKEN`).
- O histórico (Room, `AppDatabase.get`) recebe só traduções novas (`PageResult.fresh`) da bolha e da imagem de teste; fica limitado a `HISTORY_LIMIT` linhas.
- Firebase/Google Services foram removidos (não eram usados); não reintroduza sem `google-services.json` válido.

## 🧪 Diretrizes de Teste

- Sempre que criar uma nova funcionalidade de UI, considere adicionar um teste de screenshot com **Roborazzi**.
- Testes de lógica de negócio devem ser feitos com JUnit 4 e Robolectric.
- Respostas de modelo são simuladas (`ChatBackend` fake / interceptor OkHttp). `LiveTranslationTest` só roda com `MANGALENS_LIVE_TEST=1` e chave local; nunca no CI.

---
*Este arquivo deve ser atualizado sempre que houver mudanças nos requisitos de ambiente ou pipeline de build.*
