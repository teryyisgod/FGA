# Auto Story mode

Progresses main-story quests back to back: pick the next quest, select a
support, start the quest, skip/advance the story, fight with the existing
battle engine, process the results, and repeat until the configured quest
limit is reached.

## How to use

1. In **More Options → Battle**, turn on **Auto Story mode** and adjust the
   Auto Story settings (auto-skip, auto quest selection, max consecutive
   quests, stop conditions, action interval, debug log).
   The **"log battle data for AI (JSON)"** setting additionally captures a
   JSON snapshot of the battle state at battle start and at every turn's
   decision point (dealt cards, queued NPs, locked target, deployed
   servants; values FGA cannot read, such as HP, are `null`). Snapshots go
   to Logcat (`BattleObservation ...`) and are attached to the exit dialog.
   Capture is observation-only and never changes a battle decision.
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

The next quest is located by its gold "NEXT" marker: `StoryMapFinder`
template-matches `story_next.png` on the chapter map (and
`story_next_chapter.png` on the chapter-select list, so chapter
boundaries are crossed by the same search), with an OCR grid search as
fallback. Both templates were cut from real JP device screenshots
(see `docs/reference-images/Jp/quest/`). A quest is only started after
the open quest panel OCR-verifies as a Main Quest — panels of
neighbouring Free Quest spots are never started. If nothing verified
can be tapped, the script stops instead of tapping blindly.

Safety: the machine only taps on recognised screens, retries unrecognised
screens a bounded number of times, and stops on the configured conditions
(quest limit reached, AP out, defeat, unrecognised screen).
