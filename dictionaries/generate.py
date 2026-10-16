#!/usr/bin/env python3
"""Builds the curated HandHack dictionaries and dictionaries/manifest.json.

The lists are small, hand-seeded and generated deterministically so the file can be
re-run and the manifest sha256 stays stable. Every candidate is a valid WPA
passphrase (8-63 ASCII characters), so none of the work the app does is wasted.
Run from the repository root:  python dictionaries/generate.py
"""

import hashlib
import json
import os
from datetime import date, timedelta

ROOT = os.path.dirname(os.path.abspath(__file__))
MIN, MAX = 8, 63


def save(name, seq):
    seen, out = set(), []
    for raw in seq:
        s = raw.strip()
        if not s or not s.isascii():
            continue
        if not (MIN <= len(s) <= MAX):
            continue
        if s in seen:
            continue
        seen.add(s)
        out.append(s)
    blob = ("\n".join(out) + "\n").encode("utf-8")
    with open(os.path.join(ROOT, name), "wb") as handle:
        handle.write(blob)
    return len(out), len(blob), hashlib.sha256(blob).hexdigest()


def wifi_common():
    hand = [
        "123456789", "1234567890", "password1", "password123", "qwerty123",
        "qwertyui", "1q2w3e4r", "1qaz2wsx", "123456789a", "0987654321",
        "iloveyou", "loveme123", "loveyou2", "myloveyou", "internet123",
        "myinternet", "wifipassword", "wifi-password", "wifihome12", "homewifi1",
        "myhomewifi", "familywifi", "family1234", "family2019", "guest12345",
        "guestwifi", "wireless1", "mywireless", "router123", "router2020",
        "admin1234", "administrator", "nokia1234", "samsung1", "samsung123",
        "iphone123", "xiaomi123", "android123", "telefon1", "privet123",
        "jopa1234", "qwerty12", "qwerty1234", "zaq12wsx", "asdfghjk", "qazwsxed",
        "darkness", "sunshine", "letmein12", "dragonfly", "monkey123",
        "football", "football1", "baseball1", "freedom123", "shadow123",
        "welcome1", "welcome123", "mustang12", "ninja1234", "princess1",
        "diamond123", "michael1", "summer2020", "summer2021", "winter2020",
        "winter2021", "spartak99", "zenit1984", "cska1946", "liverpool",
        "mypassword", "secret123", "letmein123", "trustno1", "master123",
        "starwars1", "skywalker", "totoro123", "hello1234", "helloworld",
        "apple1234", "google123", "yandex123", "mail12345", "vkontakte",
        "instagram1", "whatsapp1", "telegram1", "moscow123", "russia123",
        "domofon123", "internet1", "internet77", "internet555", "internet777",
        "wifi777", "wifi555", "wifi111", "wifi000", "superwifi", "fastwifi",
        "freewifi123", "openguest", "guestguest", "guest1234", "zxc123456",
        "asdf12345", "qwe123456", "poiuytre", "lkjhgfds", "mnbvcxz1",
    ]
    stems = ["wifi", "internet", "password", "wireless", "guest", "home", "dom",
             "network", "router", "admin", "family", "love", "hello", "welcome",
             "secret", "summer", "winter", "malina", "kofe", "privet", "telefon",
             "internet", "super", "monkey"]
    suffixes = ["1", "12", "123", "1234", "12345", "123456", "!", "2020", "2021",
                "2022", "2023", "2024", "2019", "2018", "007", "777", "555", "01", "11"]
    out = list(hand)
    for stem in stems:
        out.append(stem)
        out.append(stem.capitalize())
        for suffix in suffixes:
            out.append(stem + suffix)
            out.append(stem.capitalize() + suffix)
    return out


def router_defaults():
    hand = [
        "admin321", "Admin123", "admin1234", "admin12345", "administrator",
        "password1", "password12", "password123", "Password1", "changeme",
        "changeme1", "letmein1", "letmein123", "default1", "default123",
        "cisco123", "cisco1234", "linksys1", "linksys123", "netgear1",
        "netgear123", "dlink123", "dlinkpass", "tplink123", "tplinkpass",
        "zyxel123", "mikrotik", "Rectangle", "ubnt1234", "ubiquiti",
        "support1", "service1", "supervisor", "management", "manager123",
        "motorola1", "technicolor", "brightbox", "belkin123", "asus1234",
        "tenda123", "totolink", "mercusys1", "keenetic", "keenetic1",
        "beeline1", "domru123", "mgts1234", "home1234", "password0",
        "1234567890", "123123123", "11111111", "00000000", "88888888",
        "qwerty123", "qwerty12", "P@ssw0rd", "Passw0rd", "wpa2pass",
        "wifikey12", "networkkey", "access123", "wireless1", "internet1",
        "rt123456", "root1234", "super1234", "totalpass", "Tp-Link_AP",
    ]
    words = ["admin", "root", "user", "support", "service", "system", "manager",
             "operator", "cisco", "linksys", "netgear", "dlink", "tplink", "zyxel",
             "ubnt", "mikrotik", "keenetic", "router", "gateway", "wireless"]
    tails = ["123", "1234", "12345", "123456", "2020", "2021", "2022", "pass",
             "password", "admin", "1", "!", "01", "2023"]
    out = list(hand)
    for word in words:
        for tail in tails:
            out.append(word + tail)
            out.append(word.capitalize() + tail)
    return out


def numeric():
    out = []
    for digit in "0123456789":
        out.append(digit * 8)
    out += ["12345678", "87654321", "11223344", "44332211", "12341234", "43214321",
            "12312312", "12121212", "13131313", "69696969", "10011001", "20022002",
            "19801980", "19901990", "20002000", "20102010", "11111111", "22222222",
            "33333333", "44444444", "55555555", "66666666", "77777777", "88888888",
            "99999999", "00000000", "12344321", "10203040", "50607080", "90807060"]
    # Birthdays written day-month-year, the most common eight-digit PIN shape.
    start = date(1975, 1, 1)
    end = date(2004, 12, 31)
    day = start
    while day <= end:
        out.append("%02d%02d%04d" % (day.day, day.month, day.year))
        day += timedelta(days=1)
    return out


def names_years():
    names = [
        "alexander", "sergey", "dmitry", "andrey", "alexey", "ivan", "maxim",
        "nikita", "michael", "mikhail", "anton", "egor", "nikolay", "vladimir",
        "pavel", "roman", "artem", "danil", "igor", "oleg", "kirill", "viktor",
        "konstantin", "yuri", "denis", "stepan", "timur", "ruslan", "gleb",
        "mark", "anna", "maria", "elena", "olga", "tatiana", "natalia", "irina",
        "svetlana", "ekaterina", "julia", "daria", "polina", "alisa", "vera",
        "ksenia", "sofia", "elizaveta", "valeria", "milana", "viktoria",
        "james", "john", "robert", "william", "david", "richard", "joseph",
        "thomas", "charles", "mary", "patricia", "jennifer", "linda",
        "elizabeth", "susan", "jessica", "sarah", "karen", "nancy", "daniel",
    ]
    out = []
    for name in names:
        for year in range(1980, 2005):
            out.append(name + str(year))
            out.append(name.capitalize() + str(year))
            out.append(name + str(year)[2:])
        out.append(name + "123")
        out.append(name.capitalize() + "123")
    return out


def keyboard():
    rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890", "qazwsxedc",
            "qwertzuiop", "poiuytrewq", "lkjhgfdsa", "mnbvcxz", "1qaz2wsx",
            "2wsx3edc", "zaqwsxcde"]
    suffixes = ["", "1", "12", "123", "!", "1234", "01"]
    out = []
    for row in rows:
        for length in range(4, len(row) + 1):
            for start in range(0, len(row) - length + 1):
                piece = row[start:start + length]
                for base in (piece, piece[::-1]):
                    for suffix in suffixes:
                        out.append(base + suffix)
    return out


def main():
    files = [
        ("wifi-common.txt", "Wi-Fi частые", "Популярные пароли домашних и гостевых сетей", wifi_common),
        ("routers-default.txt", "Роутеры по умолчанию", "Заводские пароли роутеров и точек доступа", router_defaults),
        ("numeric-8.txt", "8-значные числа", "Повторы, последовательности и даты рождения", numeric),
        ("names-years.txt", "Имена и годы", "Имена с годами и хвостами (RU/EN)", names_years),
        ("keyboard.txt", "Клавиатурные", "Прогулки по клавиатуре QWERTY и цифрам", keyboard),
    ]
    databases = []
    special = os.path.join(ROOT, "special.txt")
    if os.path.exists(special):
        blob = open(special, "rb").read()
        databases.append({
            "id": "special",
            "title": "Special",
            "detail": "Частые пароли (базовый список)",
            "file": "special.txt",
            "lines": blob.count(b"\n") + (0 if blob.endswith(b"\n") else 1),
            "bytes": len(blob),
            "sha256": hashlib.sha256(blob).hexdigest(),
            "version": 1,
            "recommended": True,
        })
    for filename, title, detail, builder in files:
        lines, size, digest = save(filename, builder())
        databases.append({
            "id": filename[:-4],
            "title": title,
            "detail": detail,
            "file": filename,
            "lines": lines,
            "bytes": size,
            "sha256": digest,
            "version": 1,
            "recommended": True,
        })
    manifest = {
        "version": 1,
        "updated": date.today().isoformat(),
        "databases": databases,
    }
    with open(os.path.join(ROOT, "manifest.json"), "w", encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    for entry in databases:
        print("%-22s %6d lines  %7d B" % (entry["file"], entry["lines"], entry["bytes"]))


if __name__ == "__main__":
    main()
