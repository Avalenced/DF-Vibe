# DF line: event:Join   (first-visit saved stats, instant respawn, welcome, lobby, per-player loop)
event('Join')
with IfVar('VarExists', var('%uuid wins', 'saved'), attribute='NOT'):
    SetVar('=', var('%uuid wins', 'saved'), num('0'))
with IfVar('VarExists', var('%uuid kills', 'saved'), attribute='NOT'):
    SetVar('=', var('%uuid kills', 'saved'), num('0'))
with IfVar('VarExists', var('%uuid rounds', 'saved'), attribute='NOT'):
    SetVar('=', var('%uuid rounds', 'saved'), num('0'))
PlayerAction('InstantRespawn', tags={'Instant Respawn': 'Enable'})
CallFunc('toLobby')
PlayerAction('SendMessage', comp('<gradient:#7ddfff:#5c9eff><bold>⚔ MINI ARENA</bold></gradient> <dark_gray>» <gray>last one standing takes the round.'))
PlayerAction('SendMessage', comp('<gray>Type <white>@start<gray> to fight, <white>@stats<gray> for your record.'))
PlayerAction('SendMessage', comp('<dark_gray>★ wins: <white>%var(%uuid wins)<dark_gray>  ·  ⚔ kills: <white>%var(%uuid kills)'))
PlayerAction('PlaySound', snd('Pling', 1.4, 1.0), tags={'Sound Source': 'Master'})
SetVar('+=', var('%uuid loopGen', 'unsaved'))
StartProcess('gameLoop', tags={'Target Mode': 'With current targets', 'Local Variables': "Don't copy"})
