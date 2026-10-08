# Mouse to Touch

App Android que traduz movimento de mouse físico (USB/Bluetooth) em toques
sintéticos, contornando o limite de borda do cursor nativo do Android —
pensado originalmente pra resolver a câmera/look no Roblox mobile, mas
funciona em qualquer jogo.

## Status

🧪 **Prototótipo funcional, não testado em produção.** A parte de captura
do mouse (Shizuku + leitura crua de `/dev/input/eventX`) foi **validada
manualmente** num POCO M3 Pro 5G rodando MIUI 14, sem root — confirmamos
que o stream de `REL_X`/`REL_Y` chega em tempo real via shell. A parte de
injeção de toque (`AccessibilityService.dispatchGesture`) e a lógica de
"arrastar e recentralizar" ainda **não foram testadas em um jogo real** —
espere precisar ajustar sensibilidade, timing e o mapeamento de botões.

## Como funciona (arquitetura)

```
[Mouse físico]
     │ deltas relativos brutos (REL_X, REL_Y, BTN_*)
     ▼
/dev/input/eventX   ← lido via "getevent -l", rodado com privilégio de
     │                 shell através do Shizuku (RawMouseReader.kt)
     ▼
[OverlayService] acumula um "cursor virtual" (x, y) sem limite de tela,
     │            recentralizando perto da borda (igual um dedo real
     │            faria ao soltar e tocar de novo)
     │
     ├──► [Overlay cosmético] desenha um quadrado vermelho na posição
     │     virtual (TYPE_APPLICATION_OVERLAY, NOT_FOCUSABLE, NOT_TOUCHABLE
     │     — nunca rouba foco de teclado nem intercepta toque)
     │
     └──► [MouseToTouchAccessibilityService] injeta o gesto de toque
           real no app em primeiro plano via dispatchGesture()
           │
           ▼
     [Jogo] recebe MotionEvent de toque normal — não sabe que não é
            um dedo de verdade.
```

### Por que duas permissões diferentes (Shizuku + Acessibilidade)?

Tentamos originalmente usar só Shizuku pra tudo (captura E injeção via
`InputManager.injectInputEvent`), mas o shell (uid 2000) **não tem** a
permissão `MONITOR_INPUT`/equivalente pra essa parte de alto nível —
isso é uma checagem feita no `system_server`, não dá pra contornar nem
com Shizuku. A solução (confirmada pela forma como o concorrente
**GG Mouse Pro** descreve publicamente seu próprio funcionamento) é
separar os dois problemas:

| Parte | Mecanismo | Permissão necessária |
|---|---|---|
| Capturar o mouse sem limite de borda | leitura crua de `/dev/input/eventX` via `getevent` | Shizuku (shell) |
| Injetar o toque no jogo | `AccessibilityService.dispatchGesture()` | Acessibilidade (concedida normalmente pelo usuário, sem Shizuku) |

## Setup pra compilar

1. Crie um repositório no GitHub e suba todo este diretório (`git init`,
   `git add .`, `git commit`, `git push`).
2. O workflow em `.github/workflows/build.yml` já compila automaticamente
   a cada push na branch `main` (ou clique em "Run workflow" manualmente
   na aba Actions do GitHub).
3. Quando o workflow terminar, baixe o APK em **Actions → (o run) →
   Artifacts → mouse-to-touch-debug-apk**.
4. No celular: habilite "Instalar apps desconhecidos" pra poder
   instalar esse APK (ele não é assinado pra loja, é build de debug).

## Setup no aparelho (depois de instalar o APK)

1. Tenha o **Shizuku** instalado e rodando (via wireless debugging —
   Configurações > Opções do desenvolvedor > Depuração sem fio — ou via
   ADB com PC).
2. Abra o Mouse to Touch e toque em:
   - **1. Conceder permissão Shizuku**
   - **2. Conceder permissão de overlay**
   - **3. Habilitar serviço de Acessibilidade** (vai abrir as
     Configurações do Android — procure "Mouse to Touch" na lista e
     ative)
3. Confirme/ajuste o caminho do dispositivo do mouse (no seu aparelho,
   foi `/dev/input/event8` — pode ser diferente; descubra rodando
   `adb shell getevent -lp` e procurando o device com `REL_X`/`REL_Y`).
4. Toque em **▶ Iniciar captura**.
5. Abra o jogo e teste.

## Pontos pra ajustar/testar (não é plug-and-play ainda)

- **Sensibilidade** (`sensitivity` em `OverlayService.kt`) e **margem de
  borda** (`edgeMargin`) provavelmente vão precisar de ajuste fino pro
  seu jogo específico.
- **Timing do `dispatchGesture`** (`durationMs` no
  `MouseToTouchAccessibilityService.kt`): essa API foi desenhada pra
  automação/acessibilidade, não pra input de jogo em tempo real — pode
  ter latência perceptível. Vale testar e ajustar.
- **Mapeamento de botões**: só o botão esquerdo está mapeado pra toque
  (clique) por padrão. Botão direito/scroll ficaram como `TODO`.
- **`Shizuku.newProcess` via reflection**: usamos isso porque é o
  workaround confirmado pela comunidade pra versões recentes da
  Shizuku-API (o método foi ocultado, ver
  [issue #276](https://github.com/RikkaApps/Shizuku-API/issues/276)).
  Se parar de funcionar numa atualização futura do Shizuku, o caminho
  oficial recomendado é migrar pra `Shizuku.UserService`.
- **Versão da dependência do Shizuku** (`dev.rikka.shizuku:api:13.1.5`
  no `app/build.gradle`): confira se ainda é a versão mais recente em
  https://github.com/RikkaApps/Shizuku-API antes de compilar, caso a
  resolução de dependência falhe.

## Aviso

Isso é uma ferramenta de acessibilidade/conveniência pra uso pessoal —
no mesmo espírito de apps como Panda Mouse Pro e GG Mouse Pro. Usar
mouse/teclado em jogos balanceados pra touch pode violar os termos de
serviço de alguns jogos ou ser considerado vantagem mecânica em modos
competitivos — isso é uma decisão sua, não uma questão técnica.
Build test
