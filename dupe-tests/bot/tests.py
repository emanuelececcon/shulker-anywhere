"""ShulkerAnywhere dupe tests against a local Paper 26.3 server, driven by a packet-level bot.

Every test counts all items the player owns (inventory, cursor, box and bundle contents, items on the ground,
placed boxes) before and after, through the SaTest helper plugin, and requires the totals to be equal. While a box
is open, the screen must show exactly what the box item holds.

    python tests.py            run everything
    python tests.py t04 t19    run the tests whose names start with these
"""
import json
import random
import sys
import time
import traceback

from mcbot import Bot, PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL
from mcbot import DROP_ITEM, DROP_ALL_ITEMS, SWAP_OFFHAND

NAME = 'Tester'
results = []
server = None
bot = None


def log(*args):
    print(*args, flush=True)


def tick(n=3):
    time.sleep(0.05 * n + 0.05)


def ledger():
    return server.ledger(NAME)


def totals(l):
    return {k: v for k, v in l['totals'].items()}


def box_at(l, slot):
    for entry in l.get('inv', []):
        if entry['slot'] == slot:
            return entry
    return None


def item_at(l, slot):
    entry = box_at(l, slot)
    return None if entry is None else (entry['id'], entry['count'])


def check(cond, what):
    if not cond:
        raise AssertionError(what)


def conserved(before, after, what=''):
    check(totals(before) == totals(after),
          'items not conserved %s: before %s after %s' % (what, json.dumps(totals(before)), json.dumps(totals(after))))


def view_is_box(l, slot):
    """The open view must show exactly what the box item in the slot holds."""
    open_ = l['open']
    check(open_['holder'] == 'Session', 'no box view open: %s' % open_)
    entry = box_at(l, slot)
    check(entry is not None and entry['id'].endswith('shulker_box'), 'no box in slot %d' % slot)
    check(sorted(map(tuple, entry.get('box', []))) == sorted(map(tuple, open_['top'])),
          'view %s != box %s' % (open_['top'], entry.get('box')))


def open_box_slot(l):
    """Slot of the box the open view shows (the bot may have switched boxes)."""
    top = sorted(map(tuple, l['open']['top']))
    slots = [e['slot'] for e in l.get('inv', []) if e['id'].endswith('shulker_box')
             and sorted(map(tuple, e.get('box', []))) == top]
    check(slots, 'the view %s matches no box in the inventory' % l['open']['top'])
    return slots


def view_closed(l):
    check(l['open']['holder'] != 'Session', 'a box view is still open')


def raw_of(player_slot):
    """Raw slot in a box view for a player inventory slot (hotbar 0-8, main 9-35)."""
    return 54 + player_slot if player_slot < 9 else player_slot + 18


def inv_raw(player_slot):
    """Raw slot in the player's own inventory screen (container 0)."""
    if player_slot < 9:
        return 36 + player_slot
    if player_slot == 40:
        return 45
    return player_slot


def fresh(setup_cmds, held=0):
    """Clean inventory and world items, run setup commands, select a hotbar slot."""
    global bot
    if bot is None or not bot.connected:
        bot = Bot(NAME, log=log)
        bot.connect()
        tick(10)
    if bot.open_container is not None:
        bot.close_container(bot.open_container)
        bot.open_container = None
    tick(2)
    server.cmd('gamemode survival %s' % NAME)
    server.cmd('satest clean %s' % NAME)
    for cmd in setup_cmds:
        reply = server.cmd(cmd)
        check('ERROR' not in reply and 'Unknown' not in reply, 'setup failed: %s -> %s' % (cmd, reply))
    bot.set_held(held)
    tick(3)


def open_from_hand(hand=0):
    bot.open_container = None
    bot.use_item(hand)
    bot.wait(lambda: bot.open_container is not None, 5, 'box view to open')
    tick(2)
    return bot.open_container


def open_from_inventory(player_slot):
    bot.open_container = None
    bot.click(0, inv_raw(player_slot), 1, PICKUP)  # right-click in the player's own inventory screen
    bot.wait(lambda: bot.open_container is not None, 5, 'box view to open')
    tick(2)
    return bot.open_container


def drag(container, raws, right=False):
    kind = 1 if right else 0
    packets = [bot.click_packet(container, -999, (kind << 2) | 0, QUICK_CRAFT)]
    packets += [bot.click_packet(container, raw, (kind << 2) | 1, QUICK_CRAFT) for raw in raws]
    packets.append(bot.click_packet(container, -999, (kind << 2) | 2, QUICK_CRAFT))
    bot.send_many(packets)


def test(fn):
    results.append(fn)
    return fn


# ------------------------------------------------------------------------------------------------------------
@test
def t01_open_from_hand_and_take_out():
    fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5' % NAME])
    before = ledger()
    c = open_from_hand()
    l = ledger(); view_is_box(l, 0); conserved(before, l)
    bot.click(c, 0, 0, PICKUP); tick()
    l = ledger(); view_is_box(l, 0); conserved(before, l, 'diamonds on cursor')
    check(l.get('cursor') and l.get('cursor')['id'] == 'minecraft:diamond', 'cursor should hold diamonds')
    bot.click(c, raw_of(10), 0, PICKUP); tick()
    l = ledger(); view_is_box(l, 0); conserved(before, l)
    check(item_at(l, 10) == ('minecraft:diamond', 64), 'diamonds should be in slot 10')
    check([0, 'minecraft:diamond', 64] not in box_at(l, 0)['box'], 'diamonds still in box')
    bot.close_container(c); tick()
    l = ledger(); view_closed(l); conserved(before, l)


@test
def t02_open_from_inventory_screen():
    fresh(['satest box %s 12 gold_ingot*20@3' % NAME])
    before = ledger()
    c = open_from_inventory(12)
    l = ledger(); view_is_box(l, 12); conserved(before, l)
    check(l.get('cursor') is None, 'the box must not be picked up by the right-click')
    bot.click(c, 3, 1, PICKUP); tick()  # right-click: half of the gold to the cursor
    l = ledger(); view_is_box(l, 12); conserved(before, l)
    bot.click(c, raw_of(20), 0, PICKUP); tick()
    l = ledger(); view_is_box(l, 12); conserved(before, l)
    check(item_at(l, 20) == ('minecraft:gold_ingot', 10), 'half the gold in slot 20, got %s' % (item_at(l, 20),))
    bot.close_container(c); tick()
    conserved(before, ledger())


@test
def t03_shift_click_both_ways():
    fresh(['satest box %s 0 diamond*64@0,emerald*30@26' % NAME, 'satest set %s 15 coal 40' % NAME])
    before = ledger()
    c = open_from_hand()
    bot.click(c, 0, 0, QUICK_MOVE); tick()
    l = ledger(); view_is_box(l, 0); conserved(before, l)
    bot.click(c, raw_of(15), 0, QUICK_MOVE); tick()
    l = ledger(); view_is_box(l, 0); conserved(before, l)
    check(any(e[1] == 'minecraft:coal' and e[2] == 40 for e in box_at(l, 0)['box']), 'coal should be in the box')
    bot.close_container(c); tick()
    conserved(before, ledger())


@test
def t04_box_slot_is_pinned():
    fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5' % NAME, 'satest box %s 7 -' % NAME,
           'satest set %s 1 stone 16' % NAME])
    before = ledger()
    c = open_from_hand()
    attempts = [
        ('pick up the box', [bot.click_packet(c, raw_of(0), 0, PICKUP)]),
        ('right-click the box', [bot.click_packet(c, raw_of(0), 1, PICKUP)]),
        ('shift-click the box', [bot.click_packet(c, raw_of(0), 0, QUICK_MOVE)]),
        ('drop the box with Q', [bot.click_packet(c, raw_of(0), 0, THROW)]),
        ('drop the box with ctrl-Q', [bot.click_packet(c, raw_of(0), 1, THROW)]),
        ('number key 1 on a box slot', [bot.click_packet(c, 0, 0, SWAP)]),
        ('number key 1 on an empty box slot', [bot.click_packet(c, 9, 0, SWAP)]),
        ('number key 1 on a player slot', [bot.click_packet(c, raw_of(20), 0, SWAP)]),
        ('number key 2 on the box', [bot.click_packet(c, raw_of(0), 1, SWAP)]),
        ('F on the box', [bot.click_packet(c, raw_of(0), 40, SWAP)]),
        ('middle-click the box', [bot.click_packet(c, raw_of(0), 2, CLONE)]),
    ]
    for what, packets in attempts:
        bot.send_many(packets); tick()
        l = ledger()
        conserved(before, l, what)
        check(item_at(l, 0) == ('minecraft:shulker_box', 1), '%s: box moved, slot 0 = %s' % (what, item_at(l, 0)))
        check(l.get('cursor') is None, '%s: cursor not empty' % what)
        view_is_box(l, 0)
    # drag stone over two box slots and the box itself: vanilla leaves the occupied box slot out
    bot.click(c, raw_of(1), 0, PICKUP); tick()
    drag(c, [10, 11, raw_of(0)]); tick()
    l = ledger(); conserved(before, l, 'drag over the box'); view_is_box(l, 0)
    check(item_at(l, 0) == ('minecraft:shulker_box', 1), 'drag moved the box')
    bot.click(c, 10, 0, PICKUP); tick(); bot.click(c, 11, 0, PICKUP); tick(); bot.click(c, raw_of(1), 0, PICKUP); tick()
    l = ledger(); conserved(before, l, 'stone back'); view_is_box(l, 0)
    # double-click with the other (empty) box on the cursor
    bot.click(c, raw_of(7), 0, PICKUP); tick()
    bot.click(c, raw_of(25), 0, PICKUP_ALL); tick()
    l = ledger(); conserved(before, l, 'double click with a box on the cursor'); view_is_box(l, 0)
    check(item_at(l, 0) == ('minecraft:shulker_box', 1), 'box gathered onto the cursor')
    bot.click(c, raw_of(7), 0, PICKUP); tick()
    bot.close_container(c); tick()
    conserved(before, ledger())


@test
def t05_off_hand_box_and_F():
    fresh(['satest box %s 40 diamond*64@0' % NAME, 'satest set %s 3 stone 5' % NAME])
    before = ledger()
    c = open_from_inventory(40)
    l = ledger(); view_is_box(l, 40)
    bot.click(c, raw_of(3), 40, SWAP); tick()   # F over stone would swap stone into the off hand
    bot.click(c, 4, 40, SWAP); tick()           # F over an empty box slot
    l = ledger(); conserved(before, l, 'F with the box in the off hand'); view_is_box(l, 40)
    check(item_at(l, 40)[0] == 'minecraft:shulker_box', 'box left the off hand')
    bot.click(c, 0, 0, PICKUP); tick(); bot.click(c, raw_of(9), 0, PICKUP); tick()
    l = ledger(); conserved(before, l); view_is_box(l, 40)
    bot.close_container(c); tick()
    conserved(before, ledger())


@test
def t06_number_keys_not_touching_the_box_work():
    fresh(['satest box %s 4 diamond*64@0,iron_ingot*10@5' % NAME])
    before = ledger()
    bot.set_held(4); tick()
    c = open_from_hand()
    bot.click(c, 0, 2, SWAP); tick()   # number key 3: diamonds to hotbar slot 2
    l = ledger(); conserved(before, l); view_is_box(l, 4)
    check(item_at(l, 2) == ('minecraft:diamond', 64), 'diamonds should be in hotbar 3')
    bot.click(c, 8, 2, SWAP); tick()   # and back into box slot 8
    l = ledger(); conserved(before, l); view_is_box(l, 4)
    check(item_at(l, 2) is None, 'hotbar 3 should be empty again')
    bot.close_container(c); tick()
    conserved(before, ledger())


@test
def t07_drop_from_the_view_is_allowed():
    fresh(['satest box %s 0 diamond*64@0' % NAME])
    before = ledger()
    c = open_from_hand()
    bot.click(c, 0, 0, THROW); tick(6)
    l = ledger(); conserved(before, l, 'Q one diamond from the view'); view_is_box(l, 0)
    check(any(e['id'] == 'minecraft:diamond' and e['count'] == 1 for e in l['entities']), 'one diamond should be on the ground')
    bot.close_container(c); tick()
    conserved(before, ledger())


@test
def t07b_drop_packet_for_the_held_box():
    fresh(['satest box %s 0 diamond*64@0' % NAME])
    before = ledger()
    bot.action(DROP_ALL_ITEMS); tick(6)
    l = ledger(); conserved(before, l, 'plain drop')
    log('    plain drop without a view: slot0=%s entities=%s' % (item_at(l, 0), l['entities']))
    fresh(['satest box %s 0 diamond*64@0' % NAME])
    c = open_from_hand()
    bot.action(DROP_ALL_ITEMS); tick(6)
    l = ledger(); conserved(before, l, 'drop while open')
    log('    drop while open: slot0=%s entities=%s open=%s' % (item_at(l, 0), l['entities'], l['open']['holder']))


@test
def t08_hacked_drop_of_the_held_box_after_a_click():
    fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5' % NAME])
    before = ledger()
    c = open_from_hand()
    bot.send_many([bot.click_packet(c, 0, 0, PICKUP), bot.click_packet(c, raw_of(9), 0, PICKUP),
                   bot.action_packet(DROP_ALL_ITEMS), bot.click_packet(c, 5, 0, PICKUP),
                   bot.click_packet(c, raw_of(10), 0, PICKUP)])
    tick(10)
    l = ledger(); conserved(before, l, 'drop packet for the held box while open')
    log('    after hacked drop: slot0=%s cursor=%s entities=%s open=%s' % (item_at(l, 0), l.get('cursor'), l['entities'], l['open']['holder']))
    tick(25)
    l = ledger(); conserved(before, l, 'after the view closed')


@test
def t09_hacked_swap_hands_place_and_pick_while_open():
    fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5' % NAME, 'satest box %s 6 -' % NAME,
           'execute at %s run setblock ~2 ~ ~ minecraft:shulker_box' % NAME])
    before = ledger()
    c = open_from_hand()
    x, y, z = int(bot.position[0] // 1), int(bot.position[1] // 1), int(bot.position[2] // 1)
    bot.send_many([bot.click_packet(c, 0, 0, PICKUP), bot.action_packet(SWAP_OFFHAND),
                   bot.use_item_on_packet((x, y - 1, z + 1), face=1), bot.click_packet(c, raw_of(9), 0, PICKUP)])
    tick(6)
    l = ledger(); conserved(before, l, 'swap hands / place while open')
    check(item_at(l, 0) == ('minecraft:shulker_box', 1), 'box moved: %s' % (item_at(l, 0),))
    view_is_box(l, 0)
    # pick block on the placed empty box would move the empty box from slot 6 into the hand
    bot.close_container(c); tick()
    bot.set_held(6); tick()
    c = open_from_hand()
    bot.set_held(2); tick()
    bot.send_many([bot.pick_block_packet((x + 2, y, z)), bot.click_packet(c, raw_of(9), 0, PICKUP),
                   bot.click_packet(c, 0, 0, PICKUP)])
    tick(6)
    l = ledger(); conserved(before, l, 'pick block while open')
    check(item_at(l, 6) == ('minecraft:shulker_box', 1), 'pick block moved the open box')
    view_is_box(l, 6)
    bot.close_container(c); tick()
    conserved(before, ledger())


@test
def t10_death_with_items_on_cursor_and_moved():
    fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5,gold_ingot*7@6' % NAME])
    before = ledger()
    c = open_from_hand()
    bot.send_many([bot.click_packet(c, 0, 0, PICKUP), bot.click_packet(c, raw_of(9), 0, PICKUP),
                   bot.click_packet(c, 5, 0, PICKUP)])
    tick()
    server.cmd('kill %s' % NAME)
    tick(10)
    l = ledger(); conserved(before, l, 'after death')
    view_closed(l)
    bot.respawn(); tick(20)
    l = ledger(); conserved(before, l, 'after respawn')


@test
def t11_death_same_batch_as_clicks():
    for i in range(3):
        fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5' % NAME, 'satest health %s 1' % NAME])
        # the kill goes through RCON, so it lands somewhere between or after the bot's clicks
        before = ledger()
        c = open_from_hand()
        bot.send_many([bot.click_packet(c, 0, 0, PICKUP), bot.click_packet(c, raw_of(9), 0, PICKUP)])
        server.cmd('kill %s' % NAME)
        bot.send_many([bot.click_packet(c, 5, 0, PICKUP), bot.click_packet(c, raw_of(10), 0, PICKUP)])
        tick(10)
        l = ledger(); conserved(before, l, 'death between clicks (run %d)' % i)
        bot.respawn(); tick(20)
        conserved(before, ledger(), 'after respawn (run %d)' % i)


@test
def t12_disconnect_with_item_on_cursor():
    global bot
    fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5' % NAME])
    before = ledger()
    c = open_from_hand()
    bot.send_many([bot.click_packet(c, 0, 0, PICKUP), bot.click_packet(c, 5, 0, PICKUP)])  # swap onto cursor
    tick()
    l = ledger(); conserved(before, l)
    bot.close_socket()
    tick(20)
    bot = Bot(NAME, log=log); bot.connect(); tick(10)
    l = ledger(); conserved(before, l, 'after reconnect'); view_closed(l)


@test
def t13_external_change_closes_the_view():
    fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5' % NAME])
    before = ledger()
    c = open_from_hand()
    server.cmd('item replace entity %s hotbar.0 with minecraft:stone 3' % NAME)
    bot.send_many([bot.click_packet(c, 0, 0, PICKUP), bot.click_packet(c, raw_of(9), 0, PICKUP)])
    tick(10)
    l = ledger()
    check(item_at(l, 9) is None and l.get('cursor') is None, 'a click went through after the box was replaced: %s' % l)
    view_closed(l)


@test
def t14_switch_between_boxes():
    fresh(['satest box %s 0 diamond*64@0' % NAME, 'satest box %s 20 emerald*12@1' % NAME])
    before = ledger()
    c = open_from_hand()
    bot.click(c, 0, 0, PICKUP); tick(); bot.click(c, raw_of(9), 0, PICKUP); tick()
    bot.open_container = None
    bot.click(c, raw_of(20), 1, PICKUP)  # right-click the other box in the lower half
    bot.wait(lambda: bot.open_container is not None, 5, 'second box view')
    tick(3)
    l = ledger(); conserved(before, l, 'switch'); view_is_box(l, 20)
    check(box_at(l, 0).get('box', []) == [], 'first box should be saved empty: %s' % box_at(l, 0))
    c2 = bot.open_container
    bot.click(c2, 1, 0, PICKUP); tick(); bot.click(c2, raw_of(11), 0, PICKUP); tick()
    bot.close_container(c2); tick()
    conserved(before, ledger())


@test
def t15_refused_boxes():
    fresh(['satest box %s 0 diamond*64@0 2' % NAME])
    bot.chat.clear()
    bot.use_item(0); tick(6)
    l = ledger(); view_closed(l)
    check(any('single shulker box' in m for m in bot.chat), 'stack refusal message missing: %s' % bot.chat)
    fresh(['item replace entity %s hotbar.0 with minecraft:shulker_box[container_loot={loot_table:"minecraft:chests/simple_dungeon"}]' % NAME])
    bot.chat.clear()
    bot.use_item(0); tick(6)
    l = ledger(); view_closed(l)
    check(any('loot' in m for m in bot.chat), 'loot refusal message missing: %s' % bot.chat)
    fresh(['item replace entity %s hotbar.0 with minecraft:shulker_box[lock={items:"minecraft:diamond"}]' % NAME])
    bot.chat.clear()
    bot.use_item(0); tick(6)
    l = ledger(); view_closed(l)
    check(any('locked' in m for m in bot.chat), 'lock refusal message missing: %s' % bot.chat)
    fresh(['item replace entity %s hotbar.0 with minecraft:shulker_box[block_entity_data={id:"minecraft:shulker_box",Items:[{Slot:0b,id:"minecraft:diamond",count:5}]}]' % NAME])
    bot.chat.clear()
    bot.use_item(0); tick(6)
    l = ledger(); view_closed(l)
    check(any('unusual data' in m for m in bot.chat), 'block entity data refusal message missing: %s' % bot.chat)
    fresh(['item replace entity %s hotbar.0 with minecraft:shulker_box[container=[{slot:30,item:{id:"minecraft:diamond",count:3}}]]' % NAME])
    bot.chat.clear()
    bot.use_item(0); tick(6)
    l = ledger(); view_closed(l)
    check(any('unusual data' in m for m in bot.chat), 'slot 30 refusal message missing: %s' % bot.chat)


@test
def t16_creative_cannot_open_and_switching_closes():
    fresh(['satest box %s 0 diamond*64@0' % NAME])
    c = open_from_hand()
    server.cmd('gamemode creative %s' % NAME)
    tick(30)
    l = ledger(); view_closed(l)
    bot.open_container = None
    bot.use_item(0); tick(6)
    check(bot.open_container is None, 'opened in creative')
    server.cmd('gamemode survival %s' % NAME)


@test
def t17_server_stop_and_restart_with_open_box():
    global bot
    fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5' % NAME])
    before = ledger()
    c = open_from_hand()
    bot.send_many([bot.click_packet(c, 0, 0, PICKUP), bot.click_packet(c, raw_of(9), 0, PICKUP),
                   bot.click_packet(c, 5, 0, PICKUP)])
    tick()
    server.stop()
    server.start()
    bot = Bot(NAME, log=log); bot.connect(); tick(10)
    l = ledger(); conserved(before, l, 'after stop and restart'); view_closed(l)


@test
def t18_crash_after_save_all():
    global bot
    fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5' % NAME])
    server.cmd('save-all flush')
    before = ledger()
    c = open_from_hand()
    bot.send_many([bot.click_packet(c, 0, 0, PICKUP), bot.click_packet(c, raw_of(9), 0, PICKUP)])
    server.cmd('save-all flush')
    bot.send_many([bot.click_packet(c, 5, 0, PICKUP), bot.click_packet(c, raw_of(10), 0, PICKUP)])
    server.cmd('save-all flush')
    server.kill()
    server.start()
    bot = Bot(NAME, log=log); bot.connect(); tick(10)
    l = ledger(); conserved(before, l, 'after a crash')


@test
def t19_fuzz_random_clicks():
    rng = random.Random(1234)
    for run in range(4):
        fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5,gold_ingot*33@13,emerald*1@26' % NAME,
               'satest box %s 7 coal*20@2' % NAME, 'satest set %s 9 stone 64' % NAME,
               'satest set %s 40 bread 12' % NAME, 'satest set %s 30 dirt 3' % NAME], held=0)
        before = ledger()
        c = open_from_hand()
        for batch in range(25):
            packets = []
            for _ in range(rng.randint(1, 6)):
                mode = rng.choice([PICKUP, PICKUP, PICKUP, QUICK_MOVE, SWAP, THROW, PICKUP_ALL, CLONE, QUICK_CRAFT])
                raw = rng.choice(list(range(63)) + [-999, raw_of(0), raw_of(0)])
                if mode == SWAP:
                    button = rng.choice([0, 1, 2, 3, 4, 5, 6, 7, 8, 40, 9])
                elif mode == QUICK_CRAFT:
                    button = rng.choice([0, 1, 2, 4, 5, 6])
                elif mode == CLONE:
                    button = 2
                else:
                    button = rng.choice([0, 1])
                packets.append(bot.click_packet(c, raw, button, mode))
            if rng.random() < 0.1:
                packets.append(bot.action_packet(rng.choice([DROP_ITEM, SWAP_OFFHAND])))
            bot.send_many(packets)
            tick(1)
        tick(10)
        l = ledger()
        conserved(before, l, 'fuzz run %d' % run)
        if l['open']['holder'] == 'Session':
            open_box_slot(l)
            bot.close_container(bot.open_container); tick()
        conserved(before, ledger(), 'fuzz run %d closed' % run)


@test
def t19b_fuzz_box_not_in_hand_and_in_off_hand():
    rng = random.Random(98765)
    for run in range(6):
        box_slot = [7, 22, 40][run % 3]
        fresh(['satest box %s %d diamond*64@0,iron_ingot*10@5,gold_ingot*33@13,emerald*1@26,bundle*1@20' % (NAME, box_slot),
               'satest box %s 30 coal*20@2' % NAME, 'satest set %s 9 stone 64' % NAME,
               'satest set %s 2 bread 12' % NAME, 'satest set %s 31 dirt 3' % NAME,
               'satest set %s 0 cobblestone 40' % NAME], held=2)
        before = ledger()
        c = open_from_inventory(box_slot)
        moved = 0
        for batch in range(40):
            packets = []
            for _ in range(rng.randint(1, 8)):
                mode = rng.choice([PICKUP, PICKUP, PICKUP, PICKUP, QUICK_MOVE, SWAP, THROW, PICKUP_ALL, QUICK_CRAFT])
                if box_slot != 40 and rng.random() < 0.1:
                    raw = raw_of(box_slot)
                else:
                    raw = rng.choice(list(range(63)) + [-999])
                if mode == SWAP:
                    button = rng.choice([0, 1, 2, 3, 4, 5, 6, 7, 8, 40])
                elif mode == QUICK_CRAFT:
                    button = rng.choice([0, 1, 2, 4, 5, 6])
                else:
                    button = rng.choice([0, 1])
                packets.append(bot.click_packet(c, raw, button, mode))
            if rng.random() < 0.15:
                packets.append(bot.action_packet(rng.choice([DROP_ITEM, DROP_ALL_ITEMS, SWAP_OFFHAND])))
            if rng.random() < 0.05:
                packets.append(bot.use_item_packet(rng.choice([0, 1])))
            bot.send_many(packets)
            tick(1)
            if batch % 10 == 9:
                l = ledger()
                conserved(before, l, 'fuzz slot %d run %d batch %d' % (box_slot, run, batch))
                if l['open']['holder'] == 'Session':
                    slots = open_box_slot(l)
                    if box_slot not in slots:
                        log('    fuzz switched from the box in slot %d to slot %s' % (box_slot, slots))
                        box_slot = slots[0]
                    moved += 1
                else:
                    break
        tick(10)
        l = ledger()
        conserved(before, l, 'fuzz slot %d run %d' % (box_slot, run))
        log('    fuzz box in slot %d: checks with the view open %d, box now %s' % (box_slot, moved, (box_at(l, box_slot) or {}).get('box')))
        if l['open']['holder'] == 'Session':
            bot.close_container(bot.open_container); tick()
        conserved(before, ledger(), 'fuzz slot %d run %d closed' % (box_slot, run))


@test
def t21_right_click_with_item_on_cursor_keeps_vanilla_meaning():
    fresh(['satest box %s 12 diamond*64@0' % NAME, 'satest set %s 13 stone 5' % NAME])
    before = ledger()
    bot.click(0, inv_raw(13), 0, PICKUP); tick()          # stone on the cursor
    bot.open_container = None
    bot.click(0, inv_raw(12), 1, PICKUP); tick(6)         # right-click the box with stone on the cursor
    check(bot.open_container is None, 'opened with an item on the cursor')
    l = ledger(); conserved(before, l)
    bot.close_container(0); tick(3)
    conserved(before, ledger())


@test
def t22_spam_open_close():
    fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5' % NAME])
    before = ledger()
    for i in range(10):
        packets = [bot.use_item_packet(0)]
        cid = bot.open_container
        if cid is not None:
            packets = [bot.click_packet(cid, 0, 0, PICKUP), bot.close_packet(cid)] + packets
        bot.send_many(packets)
        tick(2)
    tick(10)
    l = ledger(); conserved(before, l, 'spam open/close')
    if l['open']['holder'] == 'Session':
        view_is_box(l, 0)
        bot.close_container(bot.open_container); tick()
    conserved(before, ledger())


@test
def t23_disable_command_while_open():
    fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5' % NAME])
    before = ledger()
    c = open_from_hand()
    bot.click(c, 0, 0, PICKUP); tick()
    server.cmd('shulkeranywhere disable')
    tick(5)
    l = ledger(); conserved(before, l, 'after disable'); view_closed(l)
    bot.open_container = None
    bot.use_item(0); tick(6)
    check(bot.open_container is None, 'opened while disabled')
    server.cmd('shulkeranywhere enable')
    c = open_from_hand()
    l = ledger(); view_is_box(l, 0); conserved(before, l)
    bot.close_container(c); tick()


@test
def t24_death_with_keep_inventory():
    fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5' % NAME])
    server.cmd('gamerule keep_inventory true')
    server.cmd('gamerule keepInventory true')
    try:
        before = ledger()
        c = open_from_hand()
        bot.send_many([bot.click_packet(c, 0, 0, PICKUP), bot.click_packet(c, raw_of(9), 0, PICKUP),
                       bot.click_packet(c, 5, 0, PICKUP)])
        tick()
        server.cmd('kill %s' % NAME)
        tick(10)
        bot.respawn(); tick(20)
        l = ledger(); conserved(before, l, 'keepInventory death')
        check(item_at(l, 0) == ('minecraft:shulker_box', 1), 'box should be kept')
    finally:
        server.cmd('gamerule keep_inventory false')
        server.cmd('gamerule keepInventory false')


@test
def t25_teleport_to_another_dimension_while_open():
    fresh(['satest box %s 0 diamond*64@0,iron_ingot*10@5' % NAME])
    before = ledger()
    home = bot.position
    c = open_from_hand()
    bot.click(c, 0, 0, PICKUP); tick()
    server.cmd('execute in minecraft:the_end run tp %s 100 70 0' % NAME)
    tick(20)
    bot.click(c, raw_of(9), 0, PICKUP); tick(5)
    l = ledger()
    log('    after teleport: open=%s cursor=%s slot9=%s' % (l['open']['holder'], l.get('cursor'), item_at(l, 9)))
    server.cmd('execute in minecraft:overworld run tp %s %s %s %s' % (NAME, home[0], home[1], home[2]))
    tick(20)
    l = ledger(); conserved(before, l, 'teleport')


@test
def t26_bundle_inside_the_box():
    fresh(['satest box %s 0 bundle*1@0,diamond*64@1,iron_ingot*10@2' % NAME])
    before = ledger()
    c = open_from_hand()
    bot.click(c, 0, 0, PICKUP); tick()     # bundle on the cursor
    bot.click(c, 1, 1, PICKUP); tick()     # right-click diamonds: into the bundle
    bot.click(c, 2, 1, PICKUP); tick()     # and the iron
    l = ledger(); conserved(before, l, 'bundle filled'); view_is_box(l, 0)
    bot.click(c, 5, 0, PICKUP); tick()     # bundle back into box slot 5
    l = ledger(); conserved(before, l, 'bundle stored'); view_is_box(l, 0)
    bot.click(c, 5, 1, PICKUP); tick()     # right-click the bundle in the box: takes one stack out onto the cursor
    l = ledger(); conserved(before, l, 'bundle emptied one'); view_is_box(l, 0)
    bot.click(c, raw_of(9), 0, PICKUP); tick()
    bot.close_container(c); tick()
    conserved(before, ledger())


@test
def t99_log_has_no_unsaved_change_or_listener_problem():
    text = server.console_since(START_MARK)
    bad = [line for line in text.splitlines() if 'NOT in the box' in line or 'menu listener' in line
           or ('ShulkerAnywhere' in line and ('SEVERE' in line or 'ERROR' in line))]
    check(not bad, 'problems in the log:\n' + '\n'.join(bad[:20]))


START_MARK = 0


def main():
    global server, START_MARK
    from server import Server
    server = Server(log=log)
    server.start()
    START_MARK = server.console_mark()
    passed = failed = 0
    try:
        only = sys.argv[1:]
        for fn in results:
            if only and not any(fn.__name__.startswith(o) for o in only):
                continue
            try:
                fn()
                passed += 1
                log('PASS', fn.__name__)
            except Exception as e:  # noqa
                failed += 1
                log('FAIL', fn.__name__, '-', e)
                traceback.print_exc()
    finally:
        try:
            server.stop()
        except Exception:
            pass
    log('passed %d, failed %d' % (passed, failed))
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
