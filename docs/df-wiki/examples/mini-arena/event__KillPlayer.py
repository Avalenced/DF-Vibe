# DF line: event:KillPlayer   (credit the killer; the victim's Respawn does the round math)
event('KillPlayer')
SelectObj('EventTarget', tags={'Event Target': 'Killer'})
with IfVar('=', var('%uuid in', 'unsaved'), num('1')):
    SetVar('+=', var('%uuid kills', 'saved'))
    PlayerAction('SendMessage', comp('<#ff6b6b>⚔ <gray>You cut down <white>%victim<gray>.'))
    PlayerAction('PlaySound', snd('Anvil Land', 1.6, 0.5), tags={'Sound Source': 'Master'})
