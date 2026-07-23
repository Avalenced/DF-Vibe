# DF line: event:Respawn   (any death: knock the faller out; flag a winner when one remains)
event('Respawn')
with IfVar('=', var('%uuid in', 'unsaved'), num('1')):
    SetVar('=', var('%uuid in', 'unsaved'), num('0'))
    SetVar('-=', var('alive', 'unsaved'))
    PlayerAction('SendTitle', comp('<#ff6b6b>☠ OUT'), comp('<gray>%var(alive) fighters remain'), num('5'), num('40'), num('10'))
    with IfVar('=', var('arenaState', 'unsaved'), num('1')):
        with IfVar('<=', var('alive', 'unsaved'), num('1')):
            SetVar('=', var('arenaState', 'unsaved'), num('2'))
CallFunc('toLobby')
