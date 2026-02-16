# GelaFit Kiosk Manager

Projeto Android para orquestrar dois apps no tablet:

- **Servidor**: app que inicia primeiro e roda em background.
- **GelaFit GO**: app principal em modo kiosk.

## Como funciona com suas tabelas

Sem alterar schema:

- Lê `devices` por `device_id`.
- Se `is_active = true`, inicia Servidor e depois GelaFit GO.
- Se `kiosk_mode = true`, aplica politicas kiosk (quando possivel).
- Lê `device_commands` com `executed = false`.
- Comando `restart_apps`: relanca Servidor + GelaFit GO.
- Marca comando como executado (`executed = true`, `executed_at = now()`).
- Atualiza `devices.last_seen`.

## Campos necessarios na tela do app

- `Supabase URL` (ja vem preenchida por padrao)
- `API Key` (ja vem preenchida por padrao)
- `Email da unidade` (salvo em `devices.site_id`)
- `device_id` e gerado automaticamente pelo tablet
- Selecao de apps instalados para:
  - Servidor
  - GelaFit GO

Observacao: a busca de apps agora aceita digitacao para filtrar mais rapido.

## Build de APK no GitHub (sem Android Studio)

1. Crie um repositorio no GitHub e envie estes arquivos.
2. O workflow `Build APK` vai rodar automaticamente.
3. Baixe o artefato `app-debug-apk` nos resultados da Action.
4. Instale o APK no tablet.

## Permissoes e provisionamento

No app, clique em:

- `Conceder admin do dispositivo`
- `Ignorar otimizacao de bateria`

Para kiosk completo, configure como **Device Owner** (via ADB em dispositivo zerado):

```bash
adb shell dpm set-device-owner com.gelafit.kiosk/.KioskAdminReceiver
```

## Comandos remotos aceitos em `device_commands.command`

- `restart_apps`
- `start_kiosk`
- `stop_kiosk`

## Observacoes importantes

- "Nao pode ser fechado nunca" nao tem garantia absoluta em Android comum.
- Com Device Owner + foreground service + bateria sem otimizacao, a estabilidade 24/7 melhora bastante.
- Se usar Supabase com RLS, libere somente o necessario para este app acessar e atualizar o proprio `device_id`.
