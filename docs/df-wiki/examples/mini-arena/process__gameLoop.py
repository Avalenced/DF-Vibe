# DF line: process:gameLoop   (one per player: stale-loop guard, win claim, 1s action-bar cadence)
process('gameLoop', tags={'Is Hidden': 'False'})
SetVar('=', var('myGen', 'local'), var('%uuid loopGen', 'unsaved'))
with Repeat('Forever'):
    with IfVar('!=', var('myGen', 'local'), var('%uuid loopGen', 'unsaved')):
        Control('End')
    with IfVar('=', var('arenaState', 'unsaved'), num('2')):
        with IfVar('=', var('%uuid in', 'unsaved'), num('1')):
            SetVar('+=', var('%uuid wins', 'saved'))
            SetVar('=', var('arenaState', 'unsaved'), num('0'))
            PlayerAction('SendMessage', comp('<gradient:#7ddfff:#5c9eff>⚔</gradient> <dark_gray>» <#ffd86b>★ <white>%default<gray> is the last one standing!'), target='AllPlayers')
            PlayerAction('PlaySound', snd('Player Level Up', 1.0, 1.0), target='AllPlayers', tags={'Sound Source': 'Master'})
            CallFunc('toLobby')
    SetVar('+=', var('%uuid hudT', 'unsaved'))
    with IfVar('>=', var('%uuid hudT', 'unsaved'), num('4')):
        SetVar('=', var('%uuid hudT', 'unsaved'), num('0'))
        with IfVar('=', var('%uuid in', 'unsaved'), num('1')):
            PlayerAction('ActionBar', comp('<#ff6b6b>⚔ <white>%var(alive)<gray> fighters left'))
        with IfVar('=', var('%uuid in', 'unsaved'), num('0')):
            PlayerAction('ActionBar', comp('<#ffd86b>★ <white>%var(%uuid wins)<gray> wins  <dark_gray>·  <gray>@start to fight'))
    Control('Wait', num('5'), tags={'Time Unit': 'Ticks'})
