# NpcDialogue (Paper 26.3 + BetterModel)

Сборка: `gradle build` → `build/libs/NpcDialogue-1.0.0.jar` в `plugins/`. Нужен BetterModel и ресурспак NpcDialogue_ResourcePack.

## Команды
/npc — конструктор · /npc create <id> · edit/remove/tp <id> · talk <id> [игрок] · emote <id> <анимация> · reload
Shift + ПКМ по NPC (админ) — открыть редактор этого NPC.

## Диалог
Клик ЛКМ — пропустить печать / дальше. Ответ — навести прицел и кликнуть. Shift — выйти.
Текст: MiniMessage, %player% — ник. Команды ответов: `give %player% diamond 1` (консоль) или `player:spawn`.
