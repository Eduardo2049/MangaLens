# MangaLens - AI Agent Guidelines

Este documento estabelece as regras, padrões e contexto técnico do projeto MangaLens para orientar futuros agentes de IA e manter a consistência do desenvolvimento.

---

## 🚀 Contexto do Projeto
**MangaLens** é um aplicativo Android para tradução de mangás em tempo real usando sobreposição (overlay). O app utiliza o SDK do Gemini para processamento de IA e a API `MediaProjection` do Android para capturar a tela.

---

## 🛠️ Tech Stack
- **Linguagem:** Kotlin (Target JVM 17)
- **UI:** Jetpack Compose (Material 3)
- **Arquitetura:** MVVM + Clean Architecture
- **Banco de Dados:** Room
- **Rede:** Retrofit + OkHttp + Moshi
- **IA:** Google AI SDK (Gemini Multimodal)
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

## 🧪 Diretrizes de Teste

- Sempre que criar uma nova funcionalidade de UI, considere adicionar um teste de screenshot com **Roborazzi**.
- Testes de lógica de negócio devem ser feitos com JUnit 4 e Robolectric.

---
*Este arquivo deve ser atualizado sempre que houver mudanças nos requisitos de ambiente ou pipeline de build.*
