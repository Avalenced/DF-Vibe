# DF line: event:Command   (@start @stats @lobby router)
event('Command')
GameAction('CancelEvent')
with IfGame('CmdArgEquals', txt('start'), num('1'), tags={'Ignore Case': 'True'}):
    CallFunc('startRound')
with IfGame('CmdArgEquals', txt('stats'), num('1'), tags={'Ignore Case': 'True'}):
    PlayerAction('SendMessage', comp('<gradient:#7ddfff:#5c9eff>⚔</gradient> <dark_gray>» <#ffd86b>★ <white>%var(%uuid wins)<gray> wins   <#ff6b6b>⚔ <white>%var(%uuid kills)<gray> kills   <#7ddf64>▶ <white>%var(%uuid rounds)<gray> rounds'))
with IfGame('CmdArgEquals', txt('lobby'), num('1'), tags={'Ignore Case': 'True'}):
    with IfVar('=', var('%uuid in', 'unsaved'), num('1')):
        PlayerAction('SendMessage', comp('<gradient:#7ddfff:#5c9eff>⚔</gradient> <dark_gray>» <#ff6b6b>No leaving mid-round — win or fall.'))
    with IfVar('=', var('%uuid in', 'unsaved'), num('0')):
        CallFunc('toLobby')
