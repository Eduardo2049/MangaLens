# MangaLens - AI Agent Guidelines

Este documento estabelece as regras, padrões e contexto técnico do projeto MangaLens para orientar futuros agentes de IA e manter a consistência do desenvolvimento.

## 🚀 Contexto do Projeto
**MangaLens** é um aplicativo Android para tradução de mangás em tempo real usando sobreposição (overlay). O app utiliza o SDK da Gemini para processamento de IA e o serviço de acessibilidade do Android para capturar a tela.

## 🛠️ Tech Stack
- **Linguagem:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Arquitetura:** MVVM + Clean Architecture
- **Banco de Dados:** Room
- **Rede:** Retrofit + OkHttp + Moshi
- **IA:** Google AI SDK (Gemini)
- **Testes:** JUnit, Robolectric, Roborazzi (Screenshot Testing)

## 🏗️ Estrutura de Build (Importante)
- **Gradle:** 9.3.1+
- **AGP (Android Gradle Plugin):** 9.1.1
- **JDK:** Eclipse Temurin 17 (Adoptium)
- **SDK:** compileSdk 35, targetSdk 35, minSdk 24

### Regras de Build e Ambiente
1. **Assinatura Debug:** O projeto utiliza o keystore de debug padrão do Android. Nunca adicione configurações manuais de `signingConfigs { debug { ... } }` que apontem para arquivos locais específicos.
2. **Variáveis de Ambiente:** No Windows, o ambiente pode apresentar conflitos entre `ANDROID_PREFS_ROOT` e `ANDROID_USER_HOME`. A recomendação é manter apenas `ANDROID_USER_HOME`.
3. **Plugins:** O plugin `foojay-resolver-convention` deve permanecer comentado se houver erros de resolução de artefatos.
4. **Portabilidade:** Nunca adicione caminhos absolutos de arquivos locais (SDK, Keystores) em arquivos rastreados pelo Git como `gradle.properties`. Use `.env` ou variáveis de ambiente.

## 🎨 Padrões de Código
- **Composables:** Devem seguir o padrão de nomes `CamelCase`. Use `Modifier` como primeiro parâmetro opcional.
- **ViewModels:** Devem usar `StateFlow` para expor estados à UI.
- **DI:** O projeto segue padrões de injeção de dependência manuais ou simplificados (verifique a pasta `di` ou injeções nos serviços).

## 🧪 Diretrizes de Teste
- Sempre que criar uma nova funcionalidade de UI, considere adicionar um teste de screenshot com **Roborazzi**.
- Testes de lógica de negócio devem ser feitos com JUnit 4 e, se envolverem Android, Robolectric.

---
*Este arquivo deve ser atualizado sempre que houver mudanças significativas na arquitetura ou requisitos de ambiente.*
