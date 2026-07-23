# DF line: func:startRound   (@start: guard the state, count fighters, fan everyone into the arena)
func('startRound', tags={'Is Hidden': 'False'})
with IfVar('=', var('arenaState', 'unsaved'), num('1')):
    PlayerAction('SendMessage', comp('<gradient:#7ddfff:#5c9eff>⚔</gradient> <dark_gray>» <#ff6b6b>A round is already running.'))
    Control('Return')
SelectObj('AllPlayers')
SetVar('=', var('alive', 'unsaved'), gval('Selection Size'))
with IfVar('<', var('alive', 'unsaved'), num('2')):
    PlayerAction('SendMessage', comp('<gradient:#7ddfff:#5c9eff>⚔</gradient> <dark_gray>» <#ff6b6b>You need at least 2 players to start.'), target='Default')
    Control('Return')
SetVar('=', var('arenaState', 'unsaved'), num('1'))
PlayerAction('SendTitle', comp('<gradient:#7ddfff:#5c9eff><bold>FIGHT!</bold></gradient>'), comp('<gray>%var(alive) fighters — last one standing'), num('5'), num('40'), num('10'), target='Selection')
PlayerAction('PlaySound', snd('Challenge Complete', 1.0, 1.0), target='Selection', tags={'Sound Source': 'Master'})
StartProcess('arenaIn', tags={'Target Mode': 'For each in selection', 'Local Variables': "Don't copy"})
