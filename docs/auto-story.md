# Auto Story mode

Progresses main-story quests back to back: pick the next quest, select a
support, start the quest, skip/advance the story, fight with the existing
battle engine, process the results, and repeat until the configured quest
limit is reached.

## How to use

1. In **More Options → Battle**, turn on **Auto Story mode** and adjust the
   Auto Story settings (auto-skip, auto quest selection, max consecutive
   quests, stop conditions, action interval, debug log).
2. Open FGO on the story quest list (or the story map) and press FGA's play
   button. Because the start screens are shared with farming, screen
   detection reports "Battle"; with the setting above on, FGA starts
   **Auto Story** instead and shows the usual battle-config picker (the
   selected config's party, skills and support preferences are used).
3. Stop at any time with FGA's stop button.

## How it works

`AutoStory` (scripts/entrypoints) is a state machine over `StoryState`
(MAP, QUEST_SELECT, SUPPORT_SELECT, PARTY_CONFIRM, STORY, BATTLE, RESULT,
LOADING, ERROR, UNKNOWN). It reuses the existing modules: `Battle` for
combat, `Support` for support selection, `PartySelection`, `Refill`,
`Withdraw` and `ConnectionRetry`.

The story map has no template image (its art differs per chapter), so the
next quest is located by OCR: `StoryMapFinder` searches the map grid for
the "NEXT" marker above the next node. If it cannot be found, the script
stops instead of tapping blindly.

Safety: the machine only taps on recognised screens, retries unrecognised
screens a bounded number of times, and stops on the configured conditions
(quest limit reached, AP out, defeat, unrecognised screen).
