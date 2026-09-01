"""
名称生成引擎 — 基于音节表 + 马尔可夫链。
参考 wyrdbound-rng (音节组合+贝叶斯) 和 fantasy_name_generator (马尔可夫链) 的设计思路。

支持风格:
  - 东方玄幻 (修仙/武侠)
  - 西方奇幻 (DND/西幻)
  - 克苏鲁神话
  - 科幻/星战
  - 通用

生成类型:
  - character: 人物名
  - location: 地名 (城市/国家/秘境)
  - skill: 功法/技能名
  - item: 物品/神器名
  - faction: 势力/组织名
"""
from __future__ import annotations

import random
from collections import defaultdict
from typing import Any

# ═══════════════════════════════════════════════════════════
#  音节表 — 按文化风格分类
# ═══════════════════════════════════════════════════════════

SYLLABLES: dict[str, dict[str, list[str]]] = {
    # ── 东方玄幻 (修仙/武侠/古风) ──
    "eastern": {
        "prefix": [
            "张", "李", "王", "赵", "陈", "林", "叶", "秦", "萧", "楚",
            "苏", "沈", "陆", "顾", "白", "云", "柳", "江", "凌", "风",
            "司", "慕", "容", "夏", "殷", "谢", "韩", "魏", "冯", "周",
            "玄", "灵", "天", "星", "月", "雪", "冰", "炎", "雷", "苍",
            "碧", "紫", "青", "清", "明", "龙", "凤", "麒", "麟", "尘",
        ],
        "middle": [
            "逸", "清", "玄", "灵", "天", "星", "雪", "冰", "寒", "剑",
            "玉", "金", "墨", "烟", "云", "风", "霜", "露", "明", "月",
            "子", "之", "若", "无", "晓", "暮", "朝", "长", "飞", "落",
            "一", "九", "千", "万", "百", "三", "五", "七", "凌", "绝",
        ],
        "suffix": [
            "尘", "雪", "风", "云", "天", "月", "霜", "寒", "剑", "歌",
            "影", "魂", "心", "梦", "痕", "鸿", "翔", "飞", "舞", "扬",
            "瑶", "琪", "琳", "轩", "然", "峰", "岚", "玥", "莹", "希",
            "子", "之", "生", "客", "士", "君", "师", "者", "公", "翁",
        ],
        "location_prefix": [
            "苍", "玄", "天", "云", "星", "月", "落", "飞", "碧", "青",
            "紫", "白", "黑", "龙", "凤", "灵", "剑", "仙", "神", "魔",
            "九", "万", "千", "百", "太", "上", "凌", "绝", "通", "大",
        ],
        "location_suffix": [
            "山", "峰", "谷", "渊", "崖", "岭", "原", "川", "河", "江",
            "湖", "海", "城", "都", "国", "域", "界", "天", "地", "关",
            "州", "郡", "府", "县", "镇", "村", "寨", "堡", "楼", "阁",
            "殿", "堂", "洞", "府", "林", "森", "峡", "岛", "岸", "泽",
        ],
        "skill_prefix": [
            "九", "万", "千", "百", "三", "五", "七", "太", "上", "混",
            "紫", "青", "金", "玄", "灵", "天", "地", "人", "神", "魔",
            "乾", "坤", "阴", "阳", "无", "大", "小", "周", "八", "六",
            "辟", "开", "破", "碎", "灭", "生", "化", "渡", "引", "聚",
        ],
        "skill_suffix": [
            "经", "诀", "法", "功", "典", "谱", "录", "篇", "章", "卷",
            "剑", "刀", "掌", "拳", "指", "腿", "步", "身", "意", "心",
            "天", "地", "法", "道", "真", "解", "术", "气", "神", "通",
            "劲", "力", "阵", "图", "印", "咒", "符", "丹", "药", "鼎",
        ],
        "faction_prefix": [
            "天", "星", "月", "云", "风", "雷", "电", "光", "影", "无",
            "神", "仙", "魔", "妖", "鬼", "灵", "玄", "真", "太", "上",
            "苍", "碧", "紫", "青", "白", "黑", "龙", "凤", "剑", "刀",
        ],
        "faction_suffix": [
            "宗", "门", "派", "教", "帮", "会", "盟", "殿", "阁", "堂",
            "谷", "宫", "观", "院", "楼", "庄", "堡", "寨", "山", "城",
            "府", "司", "坛", "部", "军", "团", "会", "社", "坊", "行",
        ],
    },

    # ── 西方奇幻 (西幻/DND/战锤) ──
    "western": {
        "prefix": [
            "Al", "Ar", "Bal", "Bar", "Bel", "Ber", "Bran", "Bri", "Cal", "Car",
            "Cel", "Cor", "Dal", "Dar", "Dor", "Dra", "El", "Elr", "Ere", "Ery",
            "Fal", "Far", "Fel", "Fen", "Gal", "Gar", "Gil", "Gor", "Hal", "Hel",
            "Ith", "Kal", "Kar", "Kor", "Lan", "Lor", "Mar", "Mel", "Mor", "Myr",
            "Nar", "Nel", "Nor", "Ol", "Or", "Pal", "Ral", "Ran", "Ren", "Ror",
            "Sar", "Sel", "Sil", "Sol", "Sor", "Tal", "Tar", "The", "Thor", "Tor",
            "Ul", "Uri", "Val", "Van", "Var", "Vor", "Xan", "Zal", "Zar", "Zen",
        ],
        "middle": [
            "a", "ae", "an", "ar", "as", "ath", "az", "dri", "du", "el",
            "en", "er", "es", "eth", "i", "ian", "in", "ir", "is", "iv",
            "la", "li", "lo", "lu", "mar", "mir", "mor", "mul", "na", "nor",
            "o", "ol", "om", "on", "or", "os", "oth", "ra", "ran", "ras",
            "ri", "rin", "ro", "ron", "ru", "run", "sar", "sil", "sim", "sol",
            "ta", "tar", "thal", "than", "thor", "thran", "tri", "tur", "ul", "ur",
        ],
        "suffix": [
            "an", "ar", "as", "ath", "dor", "dur", "el", "en", "er", "es",
            "gar", "gon", "hal", "ian", "il", "in", "ion", "is", "ith", "ius",
            "las", "lor", "mir", "mor", "mun", "nal", "nir", "nor", "oath", "on",
            "or", "os", "oth", "rad", "ras", "ris", "ron", "ros", "rus", "thal",
            "thor", "thus", "tor", "um", "un", "und", "ur", "us", "vorn", "wyth",
        ],
        "location_prefix": [
            "Ae", "Ash", "Bar", "Black", "Bri", "Broken", "Cal", "Cape", "Dark", "Deep",
            "Dun", "Ebon", "Elder", "Ember", "Fal", "Fen", "Frost", "Gold", "Gray", "Green",
            "Haven", "High", "Iron", "Ivy", "Kings", "Lake", "Lunar", "Mist", "Moon", "Moss",
            "Nether", "Night", "North", "Obsidian", "Pale", "Red", "River", "Rune", "Shadow", "Silver",
            "Sky", "Star", "Stone", "Storm", "Sun", "Thorn", "Thunder", "White", "Wild", "Wyrm",
        ],
        "location_suffix": [
            "dale", "dell", "don", "dor", "downs", "fell", "ford", "gate", "glen", "harbor",
            "haven", "heim", "hold", "holt", "keep", "land", "march", "moor", "mor", "mouth",
            "peak", "port", "reach", "rest", "ridge", "rock", "shire", "spire", "stead", "stone",
            "vale", "wall", "wald", "ward", "water", "way", "weald", "wick", "wind", "wood",
            "wych", "wyrm", "yard", "yn", "thal", "tor", "dur", "mir", "ril", "vir",
        ],
        "skill_prefix": [
            "Arcane", "Blight", "Blood", "Bright", "Chaos", "Cleave", "Crimson", "Crystal", "Dark", "Death",
            "Demon", "Doom", "Dragon", "Earth", "Elder", "Elemental", "Ember", "Eternal", "Fallen", "Fel",
            "Flaming", "Frost", "Ghost", "Glimmer", "Golden", "Grand", "Hammer", "Healing", "Holy", "Ice",
            "Infernal", "Iron", "Life", "Light", "Lightning", "Mage", "Mind", "Mist", "Moon", "Nature",
            "Nether", "Phoenix", "Planar", "Radiant", "Raging", "Rune", "Sacred", "Shadow", "Silver", "Solar",
            "Soul", "Star", "Storm", "Sun", "Thunder", "Twilight", "Void", "War", "Wild", "Wyrm",
        ],
        "skill_suffix": [
            "blade", "bolt", "brand", "breath", "call", "chant", "circle", "claw", "cleave", "crown",
            "curse", "dance", "domain", "edge", "eye", "fall", "fang", "fire", "flame", "flare",
            "flesh", "flower", "force", "form", "fury", "gaze", "gift", "grasp", "hammer", "hand",
            "heart", "hide", "hymn", "judgment", "kiss", "lance", "light", "mark", "might", "mind",
            "nova", "pact", "path", "pulse", "rain", "reach", "ring", "rite", "roar", "sanctum",
            "seal", "shatter", "shield", "shout", "sigil", "skin", "song", "spear", "spirit", "strike",
            "storm", "sword", "touch", "veil", "ward", "wave", "whisper", "wind", "wing", "wrath",
        ],
        "faction_prefix": [
            "Arcane", "Ash", "Black", "Blood", "Bone", "Bronze", "Crimson", "Crown", "Crystal", "Dark",
            "Dawn", "Deep", "Dragon", "Dusk", "Ebon", "Emerald", "Fallen", "Fang", "Fel", "Frost",
            "Gold", "Gray", "Green", "Grim", "Guardians", "Hand", "Hollow", "Iron", "Ivory", "Knights",
            "Light", "Lunar", "Mage", "Mist", "Moon", "Night", "Obsidian", "Order", "Pale", "Radiant",
            "Red", "Ruby", "Rune", "Scarlet", "Shadow", "Silver", "Solar", "Steel", "Storm", "Sword",
            "Thorn", "Thunder", "Twilight", "Void", "War", "White", "Wild", "Wind", "Wing", "Wyrm",
        ],
        "faction_suffix": [
            "Alliance", "Army", "Band", "Brigade", "Brotherhood", "Cabal", "Cartel", "Chapter", "Circle", "Clan",
            "Company", "Confederation", "Congregation", "Conclave", "Consortium", "Council", "Court", "Covenant", "Creed", "Cult",
            "Dominion", "Empire", "Enclave", "Faction", "Fellowship", "Force", "Guild", "Horde", "Host", "Imperium",
            "Kingdom", "League", "Legion", "Lodge", "Monastery", "Nation", "Order", "Pact", "Party", "Phalanx",
            "Realm", "Regime", "Republic", "Ring", "Sect", "Senate", "Sisterhood", "Society", "Squadron", "Synod",
            "Temple", "Theocracy", "Tribe", "Union", "University", "War band",
        ],
    },

    # ── 克苏鲁神话 ──
    "cthulhu": {
        "prefix": [
            "Aza", "Cthu", "Eph", "Glaa", "Haa", "Itha", "Kha", "Lloi", "Mna", "Nya",
            "Ph", "Rly", "Sha", "Tha", "Ugg", "Vul", "Wha", "Xa", "Yog", "Zha",
            "Chth", "Dha", "Fth", "Gth", "Hl", "H'la", "K'l", "L'la", "N'ka", "Pth",
            "S'ha", "T'la", "U'la", "Vha", "Y'ha", "Z'ha", "Nyog", "Qua", "Rha", "Sthe",
        ],
        "middle": [
            "aa", "ag", "al", "am", "an", "aq", "ar", "ath", "az", "eg",
            "el", "eph", "er", "eth", "gh", "g'ha", "gth", "h'a", "h'l", "h'th",
            "i", "ig", "il", "in", "iq", "ir", "ith", "iz", "l'l", "l'la",
            "n'ag", "n'gh", "n'th", "na", "nag", "nar", "ne", "nyl", "og", "ol",
            "om", "on", "or", "oth", "q'ha", "qth", "r'l", "r'ly", "ra", "rag",
            "rn", "rr", "rth", "s'ha", "sha", "sul", "tag", "th", "th'ag", "th'l",
            "th'n", "th'ra", "tha", "thag", "thor", "thul", "tla", "tog", "tth", "ug",
            "ugl", "ul", "uln", "un", "und", "ung", "ur", "urn", "uth", "vag",
        ],
        "suffix": [
            "ath", "azoth", "el", "eth", "goth", "hoth", "lath", "lhu", "mon", "nath",
            "oth", "rath", "rhon", "roth", "thar", "thon", "thor", "thus", "thyn", "toth",
            "tur", "ulhu", "ulon", "ulus", "ura", "uran", "urath", "uron", "uth", "uzz",
            "aen", "agos", "anon", "aph", "aphon", "ar", "aris", "aros", "atan", "athoth",
            "atis", "aton", "aun", "azar", "azoth", "ean", "egoth", "emon", "eron", "ethis",
        ],
        "location_prefix": [
            "Arkham", "Carcosa", "Ce", "Dunwich", "Inns", "Kadath", "Leng", "Miskatonic", "N'kai", "Pnakot",
            "R'ly", "Sarnath", "Throk", "Ulthar", "Y'ha", "Yogg", "Zak",
        ],
        "location_suffix": [
            "ath", "gorod", "heim", "island", "moor", "mouth", "oth", "port", "ton", "topia",
            "valley", "ville", "wald", "wich", "wood", "yard",
        ],
        "faction_prefix": [
            "Brotherhood of the", "Church of", "Cult of", "Disciples of", "Eldritch", "Esoteric",
            "Hidden", "Knights of", "Lodge of", "Order of", "Seekers of", "Sisterhood of the",
            "Temple of", "The", "Worshipers of",
        ],
        "faction_suffix": [
            "Black Goat", "Black Lotus", "Black Seal", "Bloody Tongue", "Crimson King", "Dark Star",
            "Dead God", "Eternal Shadow", "Golden Dawn", "Inner Light", "Nameless Mist",
            "Outer Gate", "Silver Key", "Sleeping One", "Starry Wisdom", "Yellow Sign",
        ],
        "skill_prefix": ["Eldritch", "Unholy", "Forbidden", "Dark", "Lost"],
        "skill_suffix": [
            "Blast", "Bolt", "Chant", "Curse", "Gate", "Gaze", "Mark", "Mist", "Ritual", "Seal",
            "Shard", "Shriek", "Sigil", "Sphere", "Summoning", "Touch", "Vision", "Ward", "Wave", "Whisper",
        ],
    },

    # ── 科幻/星战 ──
    "scifi": {
        "prefix": [
            "Al", "An", "Ar", "Ax", "Ba", "Be", "Bo", "Ca", "Ce", "Ch",
            "Co", "Cor", "Cy", "Da", "De", "Di", "Do", "Dra", "Du", "Dy",
            "Ec", "El", "Em", "En", "Ep", "Er", "Ex", "Fel", "Fer", "Fi",
            "Fl", "Fo", "Fr", "Ga", "Ge", "Gi", "Go", "Gra", "Gri", "Gru",
            "Ha", "He", "Hi", "Ho", "Hy", "Ia", "Ib", "Ic", "Id", "Ig",
            "Il", "Im", "In", "Io", "Ip", "Ir", "Is", "It", "Iv", "Ix",
            "Ja", "Je", "Ji", "Jo", "Ju", "Ka", "Ke", "Ki", "Ko", "Ky",
            "La", "Le", "Li", "Lo", "Lu", "Ly", "Ma", "Me", "Mi", "Mo",
            "Mu", "My", "Na", "Ne", "Ni", "No", "Nu", "Ny", "Oc", "Og",
            "Ol", "Om", "On", "Op", "Or", "Os", "Ot", "Ox", "Pa", "Pe",
            "Ph", "Pi", "Pl", "Po", "Pr", "Ps", "Pu", "Py", "Qu", "Ra",
            "Re", "Rh", "Ri", "Ro", "Ru", "Ry", "Sa", "Sc", "Se", "Sh",
            "Si", "Sk", "Sl", "Sn", "So", "Sp", "St", "Su", "Sy", "Ta",
            "Te", "Th", "Ti", "To", "Tr", "Tu", "Ty", "Ul", "Um", "Un",
            "Ur", "Us", "Ut", "Va", "Ve", "Vi", "Vo", "Vu", "Vy", "Wa",
            "We", "Wi", "Wo", "Wu", "Wy", "Xa", "Xe", "Xi", "Xo", "Xu",
            "Xy", "Ya", "Ye", "Yi", "Yo", "Yu", "Za", "Ze", "Zi", "Zo",
            "Zu", "Zy",
        ],
        "middle": [
            "a", "ae", "an", "ar", "at", "ath", "ax", "az", "e", "en",
            "er", "es", "eth", "ex", "i", "ian", "in", "ir", "is", "ith",
            "ix", "o", "ol", "on", "or", "os", "oth", "ox", "u", "ul",
            "un", "ur", "us", "uth", "ux", "y", "yn", "ys", "yth", "yx",
            "al", "am", "as", "el", "em", "il", "im", "ol", "om", "ul",
        ],
        "suffix": [
            "an", "ar", "as", "ax", "az", "en", "er", "es", "ex", "ian",
            "id", "il", "in", "ion", "is", "ith", "ix", "oid", "on", "or",
            "os", "oth", "ox", "um", "un", "ur", "us", "uth", "ux", "yn",
            "arion", "athon", "axus", "eron", "ethis", "exus", "ianus", "idor", "igon", "ilion",
            "irus", "ithon", "onus", "oris", "orus", "othis", "oxis", "ulon", "ulus", "urion",
            "ius", "ium", "aris", "anth", "arch", "aris", "axus", "lith", "noid", "vore",
        ],
        "location_prefix": [
            "Alta", "Andro", "Anta", "Ari", "Aster", "Astro", "Atla", "Bari", "Beta", "Bore",
            "Cal", "Cali", "Cassi", "Celes", "Cent", "Cere", "Ceti", "Cor", "Cryo", "Cygn",
            "Del", "Den", "Dra", "Dro", "Ecl", "Eli", "Epsi", "Eri", "Exo", "Feli",
            "Flam", "Fron", "Gaia", "Gala", "Gem", "Halo", "Heli", "Hype", "Igni", "Iner",
            "Jovi", "Kep", "Lyr", "Mag", "Mari", "Mega", "Merc", "Meta", "Neb", "Neo",
            "Nova", "Omi", "Onyx", "Ori", "Orb", "Peg", "Pha", "Plu", "Prox", "Puls",
            "Quad", "Quan", "Quas", "Radi", "Rap", "Rie", "Rig", "Sagi", "Sata", "Scor",
            "Siri", "Sola", "Spec", "Stel", "Stra", "Tau", "Tera", "The", "Tita", "Tran",
            "Tri", "Trop", "Ult", "Umi", "Ura", "Vale", "Vega", "Velo", "Venu", "Xeno",
        ],
        "location_suffix": [
            "a", "an", "ar", "ax", "ia", "ica", "ida", "is", "ix", "on",
            "or", "os", "ox", "um", "ura", "us", "a Major", "a Minor", "ae", "ar Sector",
            "atis", "ax Belt", "e Belt", "es", "i", "ian Reach", "ica Cluster", "ida Expanse", "ion", "is Nebula",
            "ix Drift", "o", "on Field", "or Cloud", "ora", "os", "oth", "ova", "ox Drift", "um Drift",
            "un", "ura Belt", "us", "us Ring", "ux", "yn",
        ],
        "skill_prefix": [
            "A.I.", "Anti", "Bio", "Chrono", "Cryo", "Cyber", "Dimensional", "Electro", "Energy", "Entropy",
            "Exo", "Flux", "Fusion", "Gamma", "Geo", "Grav", "Hyper", "Ion", "Kinetic", "Laser",
            "Magnetic", "Mega", "Meta", "Micro", "Nano", "Neural", "Nova", "Omega", "Phase", "Photon",
            "Plasma", "Psi", "Pulse", "Quantum", "Rad", "S.A.I.", "Shield", "Sonic", "Spatial", "Sub",
            "Temporal", "Thermal", "Tractor", "Trans", "Ultra", "Void", "Warp", "Wave", "Xeno", "Zero",
        ],
        "skill_suffix": [
            "beam", "blast", "bolt", "burst", "cannon", "charge", "core", "cutter", "discharge", "drill",
            "drive", "emitter", "field", "fire", "flux", "force", "generator", "grid", "impulse", "lance",
            "laser", "matrix", "mine", "missile", "module", "net", "nexus", "pulse", "ray", "reactor",
            "rifle", "screen", "shield", "shock", "shot", "sphere", "stream", "surge", "system", "torch",
            "torpedo", "tracker", "trap", "turret", "wave", "web",
        ],
        "faction_prefix": [
            "Alpha", "Astral", "Celestial", "Cosmic", "Crystal", "Dark", "Data", "Dawn", "Deep", "Delta",
            "Eclipse", "Empyrean", "Eternal", "Federation", "Galactic", "Gamma", "Helix", "Hyper", "Imperial", "Infinite",
            "Interstellar", "Iron", "Lunar", "Mega", "Nebula", "Neo", "Nova", "Omega", "Orion", "Pegasus",
            "Phantom", "Phoenix", "Planetary", "Quantum", "Radiant", "Republic", "Rogue", "Sigma", "Solar", "Star",
            "Stellar", "Tau", "Terran", "The", "Trans", "Ultra", "United", "Universal", "Void", "Zenith",
        ],
        "faction_suffix": [
            "Alliance", "Authority", "Cartel", "Coalition", "Collective", "Command", "Confederacy", "Conglomerate", "Consortium", "Cooperative",
            "Corporation", "Council", "Dominion", "Dynasty", "Empire", "Expanse", "Federation", "Fleet", "Force", "Foundation",
            "Guild", "Hegemony", "Imperium", "Institute", "League", "Legion", "Marines", "Navy", "Order", "Pact",
            "Republic", "Sector", "Senate", "Sovereignty", "Squadron", "State", "Syndicate", "Union", "United", "Utopia",
        ],
    },
}

# ═══════════════════════════════════════════════════════════
#  马尔可夫链生成器 — 基于已有数据训练
# ═══════════════════════════════════════════════════════════


class MarkovNameGenerator:
    """基于字符级别马尔可夫链的名称生成器。参考 fantasy_name_generator 的实现思路。"""

    def __init__(self, order: int = 2):
        self.order = order
        self.chain: dict[str, list[str]] = defaultdict(list)

    def train(self, names: list[str]) -> None:
        self.chain.clear()
        for name in names:
            n = name.lower().strip()
            if not n:
                continue
            padded = "^" * self.order + n + "$"
            for i in range(len(padded) - self.order):
                key = padded[i:i + self.order]
                next_char = padded[i + self.order]
                self.chain[key].append(next_char)

    def generate(self, max_len: int = 12, min_len: int = 3) -> str:
        if not self.chain:
            return "(no training data)"
        key = "^" * self.order
        result = []
        for _ in range(max_len * 2):
            candidates = self.chain.get(key)
            if not candidates:
                break
            char = random.choice(candidates)
            if char == "$":
                break
            result.append(char)
            key = (key + char)[-self.order:]
        name = "".join(result)
        if len(name) < min_len:
            return self.generate(max_len, min_len)
        return name.capitalize()


# —— 马尔可夫训练语料：奇幻/科幻常见音节（原创词形，非照抄商业角色全名）
WESTERN_NAME_TRAINING: list[str] = [
    "elendil", "aragorn", "galadriel", "legolas", "boromir", "faramir", "eomer", "eowyn",
    "aldric", "brennon", "caldor", "darian", "elric", "finnian", "garrett", "hadrian",
    "isolde", "jorah", "kendrick", "lorelei", "morgana", "norrin", "orion", "percival",
    "quillon", "rowena", "seraph", "theron", "ulric", "valdris", "wynter", "ysandre",
    "aldwin", "branoc", "caius", "drystan", "elara", "fenric", "gareth", "halwen",
    "ivor", "jareth", "kaelen", "lyonesse", "maelis", "norbert", "orwen", "prydain",
    "ragnar", "soren", "tarquin", "ulwen", "vesper", "wystan", "xander", "yorick",
    "zorath", "aelwyn", "briony", "cormac", "deirdre", "eirwyn", "harren", "gwyneth",
]

SCIFI_NAME_TRAINING: list[str] = [
    "zenon", "krixel", "vexor", "nyx", "orion7", "helix", "quant", "vector",
    "nebula", "pulsar", "axiom", "cygnus", "draxis", "epsilon", "flux", "gravion",
    "hyper", "ion", "jett", "kepler", "lyra", "matrix", "nova", "omega",
    "photon", "quark", "radian", "solon", "tach", "ultra", "vertex", "warp",
    "xenon", "zer0", "altair", "betel", "cassi", "deneb", "eridan", "fornax",
]

CTHULHU_NAME_TRAINING: list[str] = [
    "azathoth", "cthulhu", "nyarlathotep", "yogshoth", "shubniggurath", "hastur",
    "tsathoggua", "ithaqua", "yig", "dagon", "hydra", "ghatanothoa", "zothomm",
    "nylarth", "rlyeh", "kadath", "leng", "yuggoth", "carcosa", "pnakot",
]

_MARKOV_CACHE: dict[str, MarkovNameGenerator] = {}


def _markov_person_name(style: str) -> str:
    """字符级马尔可夫，风格化人名。"""
    key = {"western": "western", "scifi": "scifi", "cthulhu": "cthulhu"}.get(style, "western")
    corpus = {
        "western": WESTERN_NAME_TRAINING,
        "scifi": SCIFI_NAME_TRAINING,
        "cthulhu": CTHULHU_NAME_TRAINING,
    }[key]
    g = _MARKOV_CACHE.get(key)
    if g is None:
        g = MarkovNameGenerator(order=3)
        g.train(corpus)
        _MARKOV_CACHE[key] = g
    raw = g.generate(max_len=14, min_len=5)
    if not raw or raw.startswith("("):
        return _syllable_name(style, "character", 2, 4)
    return raw[0].upper() + raw[1:] if raw else _syllable_name(style, "character", 2, 3)


def _latin_skill_name(style: str) -> str:
    syl = SYLLABLES.get(style, SYLLABLES["western"])
    sp = syl.get("skill_prefix", syl["prefix"])
    ss = syl.get("skill_suffix", syl["suffix"])
    if random.random() < 0.42:
        a, b = random.choice(sp), random.choice(ss)
        return f"{a} {b.title()}" if random.random() < 0.55 else f"{a} {b}"
    return random.choice(sp) + random.choice(ss)


def _latin_item_name(style: str) -> str:
    syl = SYLLABLES.get(style, SYLLABLES["western"])
    mats = syl.get("skill_prefix", syl["prefix"])
    shapes = [x for x in syl.get("skill_suffix", []) if len(x) >= 4][:24] or syl.get("skill_suffix", ["relic"])
    if random.random() < 0.5:
        return f"{random.choice(mats)} {random.choice(shapes).title()}"
    return random.choice(mats) + random.choice(shapes)


# ═══════════════════════════════════════════════════════════
#  主生成器 API
# ═══════════════════════════════════════════════════════════

STYLE_NAMES = {
    "eastern": "东方玄幻",
    "western": "西方奇幻",
    "cthulhu": "克苏鲁神话",
    "scifi": "科幻/星战",
}

GENERATE_TYPES = [
    {"id": "character", "label": "人物名"},
    {"id": "location", "label": "地名"},
    {"id": "skill", "label": "功法/技能名"},
    {"id": "item", "label": "物品/神器名"},
    {"id": "faction", "label": "势力/组织名"},
]


def _syllable_name(style: str, name_type: str, min_syllables: int = 2, max_syllables: int = 4) -> str:
    """基于音节组合生成名称。参考 wyrdbound-rng 的 simple 算法。"""
    syl = SYLLABLES.get(style)
    if not syl:
        syl = SYLLABLES["western"]

    # 根据类型选择合适的前缀/后缀表
    prefix_key = f"{name_type}_prefix" if f"{name_type}_prefix" in syl else "prefix"
    suffix_key = f"{name_type}_suffix" if f"{name_type}_suffix" in syl else "suffix"
    middle_key = "middle"

    prefixes = syl.get(prefix_key, syl["prefix"])
    suffixes = syl.get(suffix_key, syl["suffix"])
    middles = syl.get(middle_key, [])

    # 决定音节数
    syllable_count = random.randint(min_syllables, max_syllables)

    if syllable_count == 2:
        # prefix + suffix
        return random.choice(prefixes) + random.choice(suffixes)
    elif syllable_count == 3:
        # prefix + middle + suffix
        return random.choice(prefixes) + random.choice(middles) + random.choice(suffixes)
    else:
        parts = [random.choice(prefixes)]
        for _ in range(syllable_count - 2):
            parts.append(random.choice(middles))
        parts.append(random.choice(suffixes))
        return "".join(parts)


def _faction_name_western(style: str) -> str:
    """西式/科幻势力：复合前缀与后缀之间补空格；少量 the + 形容词 + 后缀。"""
    syl = SYLLABLES.get(style, SYLLABLES["western"])
    fp_all = syl.get("faction_prefix", syl["prefix"])
    fs = syl.get("faction_suffix", syl["suffix"])
    compound = [p for p in fp_all if " " in p.strip()]
    simple = [p for p in fp_all if " " not in p.strip()]
    if compound and random.random() < 0.48:
        pref = random.choice(compound)
        suf = random.choice(fs)
        join = "" if pref.endswith((" ", "'")) else " "
        return (pref + join + suf).replace("  ", " ").strip()
    if simple and random.random() < 0.2:
        adj = random.choice(["Elder", "Arcane", "Crimson", "Ashen", "Shattered", "Silent"])
        suf = random.choice(fs)
        return f"the {adj} {suf}"
    if simple:
        return random.choice(simple) + random.choice(fs)
    return random.choice(fp_all) + random.choice(fs)


def _location_name_western(style: str) -> str:
    """西式/科幻地名：前缀+后缀；少量 the + 形容词 + 地理后缀。"""
    syl = SYLLABLES.get(style, SYLLABLES["western"])
    lp = syl.get("location_prefix", syl["prefix"])
    ls = syl.get("location_suffix", syl["suffix"])
    prefix = random.choice(lp)
    suffix = random.choice(ls)
    if random.random() < 0.28:
        adj = random.choice(["Ancient", "Dark", "Golden", "Lost", "Misty", "Silent", "Eternal", "Crimson"])
        return f"the {adj} {suffix}"
    return f"{prefix}{suffix}"


def _sanitize(name: str) -> str:
    """拉丁名首字母大写；the 开头时后续词 Title Case，避免 the Orderof 一类畸形。"""
    name = name.strip()
    if not name:
        return "无名"
    if name.lower().startswith("the "):
        rest = name[4:].strip()
        if not rest:
            return "the"
        parts = [p for p in rest.split() if p]
        titled = " ".join((p[0].upper() + p[1:].lower()) if len(p) > 1 else p.upper() for p in parts)
        return f"the {titled}"
    if name[0].islower():
        name = name[0].upper() + name[1:]
    return name.strip()


def generate_names(
    style: str = "western",
    name_type: str = "character",
    count: int = 5,
    existing: list[str] | None = None,
) -> list[dict[str, Any]]:
    """生成名称列表。

    Args:
        style: 风格 (eastern/western/cthulhu/scifi)
        name_type: 类型 (character/location/skill/item/faction)
        count: 生成数量
        existing: 已有名称列表，避免重复

    Returns:
        名称列表，每项含 name/romanized/meaning 字段
    """
    existing_set = set(n.lower() for n in (existing or []))
    results = []
    attempts = 0

    while len(results) < count and attempts < count * 20:
        attempts += 1
        name = ""

        if style == "eastern":
            if name_type == "character":
                name = _eastern_character_name()
            elif name_type == "location":
                name = _eastern_location_name()
            elif name_type == "skill":
                name = _eastern_skill_name()
            elif name_type == "item":
                name = _eastern_item_name()
            elif name_type == "faction":
                name = _eastern_faction_name()
            else:
                name = _syllable_name(style, name_type)
        elif name_type == "faction" and style in ("western", "scifi", "cthulhu"):
            name = _faction_name_western(style)
        elif name_type == "location" and style in ("western", "scifi", "cthulhu"):
            name = _location_name_western(style)
        elif name_type == "skill" and style in ("western", "scifi"):
            name = _latin_skill_name(style)
        elif name_type == "item" and style in ("western", "scifi", "cthulhu"):
            name = _latin_item_name(style)
        elif name_type == "character" and style in ("western", "scifi", "cthulhu"):
            name = _markov_person_name(style) if random.random() < 0.74 else _syllable_name(style, "character")
        else:
            name = _syllable_name(style, name_type)

        name = _sanitize(name)
        if name.lower() in existing_set:
            continue
        existing_set.add(name.lower())

        meaning = _meaning_for_name(style, name_type, name)
        results.append({"name": name, "meaning": meaning})

    return results[:count]


def _random_meaning(style: str, name_type: str) -> str:
    """随机生成一个名称含义解释。"""
    eastern_meanings = [
        "取自《易经》乾卦",
        "源自上古神兽之名",
        "为纪念开派祖师而名",
        "取'天地玄黄'之意",
        "源自天象感应",
        "取'道法自然'之理",
    ]
    western_meanings = [
        "源自古老精灵语",
        "意为'北方之星'",
        "在古语中意为'火焰之子'",
        "传说中以首位定居者命名",
        "意为'永恒之光'",
        "龙语中意为'山脉之心'",
    ]
    scifi_meanings = [
        "标准星区编号衍生名",
        "以先驱探险家命名",
        "古代地球神话衍生",
        "星系坐标旧称",
        "殖民时代命名",
        "科研基地代号流传",
    ]
    cthulhu_meanings = [
        "不可名状的低语中提取",
        "《死灵书》残卷记载",
        "噩梦中反复出现的音节",
        "远古石碑上的铭文",
        "无法考证的神秘称谓",
    ]

    pool = eastern_meanings if style == "eastern" else western_meanings if style == "western" else scifi_meanings if style == "scifi" else cthulhu_meanings
    return random.choice(pool)


# ═══════════════════════════════════════════════════════════
#  东方玄幻 — 结构化生成（与旧版「prefix/middle/suffix 全表混抽」解耦）
#  网文常见：人名以「姓 + 有语义双字名」为主；地名「意象 + 地理后缀」；
#  功法「数字/方位 + 意象 + 经诀篇」；物品「材料/意象 + 形制」。
# ═══════════════════════════════════════════════════════════

EASTERN_SURNAMES_SINGLE: list[str] = [
    "赵", "钱", "孙", "李", "周", "吴", "郑", "王", "冯", "陈", "褚", "卫", "蒋", "沈", "韩", "杨",
    "朱", "秦", "尤", "许", "何", "吕", "施", "张", "孔", "曹", "严", "华", "金", "魏", "陶", "姜",
    "戚", "谢", "邹", "喻", "柏", "水", "窦", "章", "云", "苏", "潘", "葛", "奚", "范", "彭", "郎",
    "鲁", "韦", "昌", "马", "苗", "凤", "花", "方", "俞", "任", "袁", "柳", "鲍", "史", "唐", "费",
    "廉", "岑", "薛", "雷", "贺", "倪", "汤", "滕", "殷", "罗", "毕", "郝", "邬", "安", "常", "乐",
    "于", "时", "傅", "皮", "卞", "齐", "康", "伍", "余", "元", "卜", "顾", "孟", "平", "黄", "和",
    "穆", "萧", "尹", "姚", "邵", "湛", "汪", "祁", "毛", "禹", "狄", "米", "贝", "明", "臧", "计",
    "伏", "成", "戴", "谈", "宋", "茅", "庞", "熊", "纪", "舒", "屈", "项", "祝", "董", "梁", "杜",
    "阮", "蓝", "闵", "席", "季", "麻", "强", "贾", "路", "娄", "危", "江", "童", "颜", "郭", "梅",
    "盛", "林", "刁", "钟", "徐", "邱", "骆", "高", "夏", "蔡", "田", "樊", "胡", "凌", "霍", "虞",
    "万", "支", "柯", "昝", "管", "卢", "莫", "经", "房", "裘", "缪", "干", "解", "应", "宗", "丁",
    "宣", "贲", "邓", "郁", "单", "杭", "洪", "包", "诸", "左", "石", "崔", "吉", "钮", "龚", "程",
    "嵇", "邢", "滑", "裴", "陆", "荣", "翁", "荀", "羊", "於", "惠", "甄", "曲", "家", "封", "芮",
    "羿", "储", "靳", "汲", "邴", "糜", "松", "井", "段", "富", "巫", "乌", "焦", "巴", "弓", "牧",
    "隗", "山", "谷", "车", "侯", "宓", "蓬", "全", "郗", "班", "仰", "秋", "仲", "伊", "宫", "宁",
]

EASTERN_SURNAMES_COMPOUND: list[str] = [
    "欧阳", "上官", "慕容", "诸葛", "东方", "司徒", "司空", "司马", "南宫", "西门", "皇甫", "尉迟", "公孙", "轩辕",
]

# 双字「名」：偏网文常用意象组合（原创词表，非照抄具体作品角色全名）
EASTERN_GIVEN_DOUBLE: list[str] = [
    "子羽", "子陵", "子期", "子墨", "子瑜", "子衿", "子渊", "子昂", "子恒", "子谦",
    "青岚", "青冥", "青霜", "青阳", "青云", "青竹", "青梧", "青鸾", "青璃", "青玄",
    "若雪", "若璃", "若尘", "若渊", "若水", "若兰", "若溪", "若瑜", "若鸿", "若舟",
    "梦瑶", "梦璃", "梦珂", "梦岚", "梦竹", "梦华", "梦舟", "梦渔", "梦泽", "梦渊",
    "长歌", "长风", "长卿", "长渊", "长明", "长离", "长夜", "长川", "长陵", "长亭",
    "无心", "无尘", "无咎", "无涯", "无妄", "无忌", "无殇", "无渊", "无霜", "无月",
    "凌霄", "凌渊", "凌尘", "凌霜", "凌雪", "凌川", "凌岳", "凌澜", "凌洲", "凌夜",
    "寒烟", "寒月", "寒川", "寒渊", "寒江", "寒松", "寒梅", "寒竹", "寒星", "寒夜",
    "星河", "星澜", "星落", "星渊", "星野", "星罗", "星痕", "星遥", "星澈", "星沉",
    "月华", "月明", "月瑶", "月璃", "月汐", "月岚", "月笙", "月影", "月白", "月泠",
    "云深", "云澈", "云归", "云舟", "云岚", "云舒", "云昭", "云渊", "云歌", "云栖",
    "清漪", "清岚", "清霜", "清和", "清玄", "清微", "清晏", "清越", "清辞", "清让",
    "玄策", "玄渊", "玄夜", "玄霄", "玄冥", "玄玑", "玄曜", "玄羽", "玄尘", "玄青",
    "墨渊", "墨尘", "墨竹", "墨言", "墨卿", "墨白", "墨池", "墨痕", "墨离", "墨遥",
    "景行", "景明", "景曜", "景渊", "景和", "景珩", "景辞", "景澜", "景澈", "景宁",
    "怀瑾", "怀瑜", "怀安", "怀远", "怀渊", "怀玉", "怀霜", "怀川", "怀岳", "怀风",
    "知微", "知渊", "知白", "知秋", "知遥", "知澜", "知澈", "知言", "知让", "知衡",
    "念卿", "念瑶", "念汐", "念尘", "念渊", "念白", "念霜", "念川", "念远", "念安",
    "晚棠", "晚晴", "晚照", "晚舟", "晚吟", "晚风", "晚星", "晚月", "晚秋", "晚宁",
    "疏影", "疏桐", "疏寒", "疏澜", "疏月", "疏星", "疏云", "疏风", "疏雨", "疏钟",
]

EASTERN_GIVEN_SINGLE: list[str] = [
    "珩", "瑜", "璟", "琛", "珂", "玥", "瑶", "璃", "珂", "璇",
    "渊", "澈", "澜", "洲", "岳", "川", "澜", "浔", "湛", "泓",
    "宸", "奕", "昱", "昭", "晖", "曜", "晟", "旻", "昀", "昕",
    "辞", "让", "谦", "谨", "恪", "忱", "忱", "恪", "谌", "谧",
    "骁", "骐", "骥", "骞", "骜", "骧", "骊", "骍", "骝", "骃",
]

EASTERN_LOC_PREFIX2: list[str] = [
    "青云", "碧海", "苍山", "明月", "落日", "断崖", "白骨", "琉璃", "九曲", "千丈",
    "天风", "云海", "紫禁", "药王", "幽冥", "忘川", "轮回", "归墟", "蓬莱", "方丈",
    "昆仑", "瑶池", "弱水", "赤水", "黑水", "流沙", "鸣沙", "玉龙", "雪峰", "寒铁",
    "赤焰", "玄冰", "紫霄", "金庭", "银沙", "铜鼓", "铁壁", "木鱼", "竹溪", "松林",
    "桃花", "梨花", "杏花", "梅雨", "霜降", "白露", "春分", "秋分", "冬至", "夏至",
]

EASTERN_SKILL_TEMPLATES: list[str] = [
    "{a}{b}{suf}",
    "{num}{a}{b}{suf}",
    "{a}{b}{c}{suf}",
    "{dir}{a}{suf}",
]

EASTERN_SKILL_CORE_A: list[str] = [
    "太乙", "太玄", "太上", "太清", "紫霄", "青冥", "玄阴", "玄阳", "混元", "乾坤",
    "阴阳", "五行", "八卦", "九宫", "六合", "周天", "星斗", "日月", "风云", "雷霆",
]

EASTERN_SKILL_CORE_B: list[str] = [
    "分光", "御剑", "凝霜", "焚天", "镇狱", "锁龙", "伏魔", "诛邪", "破妄", "归真",
    "御空", "踏虚", "移形", "换影", "夺魄", "摄魂", "斩念", "明心", "见性", "通神",
]

EASTERN_SKILL_CORE_C: list[str] = ["真解", "秘要", "精义", "奥义", "残篇", "全本", "上篇", "下篇", "外篇", "内篇"]

EASTERN_SKILL_SUFFIX: list[str] = [
    "经", "诀", "典", "录", "谱", "篇", "章", "卷", "法", "功",
    "剑诀", "刀谱", "掌法", "拳经", "指诀", "身法", "心法", "秘录", "真诀", "玄功",
]

# 道教经法语感：教派/三洞前缀 + 境物中缀 + 经箓后缀（词组为常见宗教语汇组合，随机拼接成原创篇名）
TAO_LIT_PREFIX: list[str] = [
    "太上", "上清", "玉清", "灵宝", "洞真", "洞玄", "洞神",
    "正一", "天师", "妙真", "大洞", "玄一", "紫清", "太玄", "玄门",
]
TAO_LIT_INFIX: list[str] = [
    "洞渊", "玉晨", "紫薇", "黄庭", "升玄", "无量", "归一", "混沌",
    "盟威", "清微", "紫庭", "内景", "外景", "景霄", "九真", "八素",
    "金箓", "定观", "飞步", "存神", "守一", "玄机", "明堂", "三景",
]
TAO_LIT_SUFFIX: list[str] = [
    "秘录", "真经", "上品经", "内景经", "外景经", "真诀", "心印",
    "宝箓", "玄义", "大法", "心传", "玄章", "科", "中法", "上品法",
]

# 民俗法事 / 旁门法本语感（原创组合，非复刻具体科书篇名）
FOLK_LIT_PREFIX: list[str] = [
    "茅山", "闾山", "梅山", "巫门", "坛门", "香火", "民间", "乡土", "走阴", "傩门",
    "法主", "敕封", "旁门", "外坛", "社祭", "宗族", "祠堂", "阴司", "阳世", "打醮",
]
FOLK_LIT_INFIX: list[str] = [
    "敕令", "符水", "过关", "送亡", "延嗣", "安坛", "镇煞", "押煞", "开财", "和合",
    "斩邪", "破土", "谢土", "召将", "押阵", "安神", "安龙", "开坛", "押船", "送灶",
]
FOLK_LIT_SUFFIX: list[str] = [
    "科仪", "秘本", "心法", "口授", "真形", "宝诰", "咒心", "法本", "心传", "实录",
    "简本", "醮章", "规仪", "手抄", "秘传", "科", "大法", "玄章",
]

# 国漫 / 轻小说式修仙语感（原创词形，避免整句照抄知名作品专名）
ANIME_XIANXIA_PREFIX: list[str] = [
    "星穹", "虚界", "逆潮", "零式", "界律", "灵子", "相位", "终末", "万华", "断罪",
    "序列", "虚空", "时序", "超限", "悖论", "陨星", "再临", "万界", "影契", "魂锁",
]
ANIME_XIANXIA_INFIX: list[str] = [
    "演武", "折跃", "权柄", "禁制", "共鸣", "重构", "虚演", "暴走", "枷锁", "崩解",
    "回响", "干涉", "裁断", "逆流", "升格", "降格", "刻印", "改写", "观测", "锚定",
]
ANIME_XIANXIA_SUFFIX: list[str] = [
    "抄", "式", "秘仪", "真解", "残章", "奥义书", "禁手本", "咒式", "序列册", "界钥",
    "真名篇", "权能录", "虚数经", "演武谱", "心像诀", "灵子篇", "终式", "零式篇",
]

# 势力名：在原有仙门词头外增加民俗坛口与国漫式盟会语感
FOLK_FACTION_HEAD: list[str] = [
    "茅山", "闾山", "梅山", "巫门", "香火", "坛口", "傩师", "法主", "社祭", "宗族",
    "走阴", "旁门", "外坛", "阴司", "阳世", "打醮",
]
ANIME_FACTION_HEAD: list[str] = [
    "星穹", "虚界", "万华", "断罪", "零式", "时序", "逆潮", "万界", "影契", "灵子",
    "界律", "序列", "终末", "陨星", "悖论",
]

EASTERN_SKILL_NUM: list[str] = ["", "", "", "三", "五", "七", "九", "十二", "三十六"]

EASTERN_SKILL_DIR: list[str] = ["东", "西", "南", "北", "中", "上", "下", "内", "外", "天", "地"]

EASTERN_ITEM_MATERIAL: list[str] = [
    "玄铁", "寒铁", "赤铜", "秘银", "星辰", "陨星", "龙鳞", "凤羽", "麒麟", "白虎",
    "青木", "玄冰", "赤焰", "紫电", "金光", "幽冥", "黄泉", "碧落", "琉璃", "紫金",
]

EASTERN_ITEM_SHAPE: list[str] = [
    "剑", "刀", "枪", "戟", "鞭", "锏", "锤", "斧", "弓", "弩",
    "扇", "铃", "镜", "鼎", "炉", "珠", "环", "佩", "簪", "镯",
    "履", "袍", "甲", "盾", "符", "印", "幡", "塔", "钟", "鼓",
]

EASTERN_FACTION_HEAD: list[str] = [
    "天剑", "无涯", "造化", "玄门", "万法", "青云", "碧海", "凌霄", "太虚", "混元",
    "紫霄", "青冥", "玄阴", "真武", "灵溪", "听雨", "观潮", "抱朴", "守一", "明德",
    "长生", "归真", "明心", "见性", "伏魔", "镇邪", "诛妖", "御龙", "踏天", "摘星",
]


def _pick(seq: list[str]) -> str:
    return random.choice(seq)


def _eastern_character_name() -> str:
    r = random.random()
    if r < 0.06:
        return _pick(EASTERN_SURNAMES_COMPOUND) + _pick(EASTERN_GIVEN_DOUBLE)
    if r < 0.22:
        return _pick(EASTERN_SURNAMES_SINGLE) + _pick(EASTERN_GIVEN_SINGLE)
    if r < 0.92:
        return _pick(EASTERN_SURNAMES_SINGLE) + _pick(EASTERN_GIVEN_DOUBLE)
    # 单姓 + 单字辈 + 单字名（略增古风）
    b = _pick(["子", "之", "文", "景", "怀", "长", "云", "清", "玄", "慕"])
    return _pick(EASTERN_SURNAMES_SINGLE) + b + _pick(EASTERN_GIVEN_SINGLE)


def _eastern_location_name() -> str:
    r = random.random()
    suf = _pick(SYLLABLES["eastern"]["location_suffix"])
    if r < 0.58:
        return _pick(EASTERN_LOC_PREFIX2) + suf
    if r < 0.82:
        p = _pick(SYLLABLES["eastern"]["location_prefix"])
        core = _pick(EASTERN_LOC_PREFIX2)
        return p + core + suf if random.random() < 0.45 else p + suf
    a = _pick(["九", "万", "千", "百", "太", "上", "凌", "绝", "通", "大"])
    b = _pick(EASTERN_LOC_PREFIX2)
    return a + b + suf


def _tao_lit_combo() -> str:
    """道教经箓式：前缀 + 中缀 + 后缀等变体。"""
    r = random.random()
    p, i, s = _pick(TAO_LIT_PREFIX), _pick(TAO_LIT_INFIX), _pick(TAO_LIT_SUFFIX)
    if r < 0.58:
        return p + i + s
    if r < 0.82:
        bridge = _pick(["内景", "外景", "八景", "三景", "九真", "玄机"])
        return p + bridge + s
    dong = _pick(["洞真", "洞玄", "洞神"])
    tail = _pick(["中经", "真经", "上品经", "玄义", "秘录"])
    return dong + _pick(TAO_LIT_INFIX) + tail


def _folk_lit_skill() -> str:
    """民俗法事式：坛口/旁门 + 法事动词块 + 科仪类后缀。"""
    r = random.random()
    p, i, s = _pick(FOLK_LIT_PREFIX), _pick(FOLK_LIT_INFIX), _pick(FOLK_LIT_SUFFIX)
    if r < 0.62:
        return p + i + s
    if r < 0.88:
        return p + _pick(["敕封", "延请", "安奉", "押送", "召请", "禳解"]) + i + s
    return p + s


def _anime_xianxia_skill() -> str:
    """国漫修仙式：大词头 + 中二技法块 + 轻小说式后缀。"""
    r = random.random()
    p, i, s = _pick(ANIME_XIANXIA_PREFIX), _pick(ANIME_XIANXIA_INFIX), _pick(ANIME_XIANXIA_SUFFIX)
    if r < 0.55:
        return p + i + s
    if r < 0.82:
        return p + _pick(["超限", "临界", "悖论", "虚数", "观测"]) + i + s
    return p + _pick(["终式", "零式", "界钥", "权能"]) + s


def _eastern_liturgical_skill() -> str:
    """经箓 / 民俗 / 国漫修仙 三套语感随机，外观唬人即可。"""
    r = random.random()
    if r < 0.36:
        return _tao_lit_combo()
    if r < 0.68:
        return _folk_lit_skill()
    return _anime_xianxia_skill()


def _eastern_taoist_liturgical_skill() -> str:
    """兼容旧名：等同道教分支（供测试或外部引用）。"""
    return _tao_lit_combo()


def _eastern_skill_name() -> str:
    if random.random() < 0.52:
        return _eastern_liturgical_skill()
    tpl = _pick(EASTERN_SKILL_TEMPLATES)
    a, b, c = _pick(EASTERN_SKILL_CORE_A), _pick(EASTERN_SKILL_CORE_B), _pick(EASTERN_SKILL_CORE_C)
    suf = _pick(EASTERN_SKILL_SUFFIX)
    num = _pick(EASTERN_SKILL_NUM)
    d = _pick(EASTERN_SKILL_DIR)
    if tpl == "{a}{b}{suf}":
        return a + b + suf
    if tpl == "{num}{a}{b}{suf}":
        return (num or _pick(["三", "九"])) + a + b + suf
    if tpl == "{a}{b}{c}{suf}":
        return a + b + c + suf
    return d + a + suf


def _eastern_item_name() -> str:
    r = random.random()
    mat = _pick(EASTERN_ITEM_MATERIAL)
    shp = _pick(EASTERN_ITEM_SHAPE)
    if r < 0.45:
        return mat + shp
    if r < 0.75:
        return mat + _pick(["断", "碎", "藏", "隐", "无名", "残", "古", "秘"]) + shp
    grade = _pick(["凡", "灵", "玄", "地", "天", "圣"])
    return f"{grade}品{mat}{shp}"


def _eastern_faction_name() -> str:
    suf = _pick(SYLLABLES["eastern"]["faction_suffix"])
    r = random.random()
    if r < 0.22:
        head = _pick(FOLK_FACTION_HEAD)
    elif r < 0.42:
        head = _pick(ANIME_FACTION_HEAD)
    else:
        head = _pick(EASTERN_FACTION_HEAD)
    return head + suf


def _meaning_for_name(style: str, name_type: str, name: str) -> str:
    """按名中关键字给「像样」的释义，避免与具体名字完全无关的套话。"""
    if style == "eastern":
        triggers: list[tuple[str, str]] = [
            ("剑", "名中带「剑」，多主杀伐锋锐之气。"),
            ("刀", "名中带「刀」，偏刚猛直断之路。"),
            ("宗", "以「宗」为号，常为道统所系之门派。"),
            ("门", "以「门」为号，多为江湖门户。"),
            ("谷", "以「谷」为名，多指幽深藏锋之地。"),
            ("渊", "「渊」象水深难测，多喻底蕴或险地。"),
            ("霄", "「霄」象高远云天，多喻志向或天象。"),
            ("瑶", "「瑶」为美玉之象，多喻温润或贵气。"),
            ("尘", "「尘」象人间烟火，亦常寓出世之叹。"),
            ("星", "「星」象周天列宿，多涉天机或远行。"),
            ("月", "「月」象阴晴圆缺，多涉阴柔或潮汐。"),
            ("诀", "以「诀」为名，多为口传心授之秘要。"),
            ("经", "以「经」为名，多为体系完备之大法。"),
            ("谱", "以「谱」为名，多为招式或器纹之总成。"),
            ("品", "「品」级之名，多见于器物阶位之分。"),
            ("九", "「九」数之极，多寓圆满或劫数。"),
            ("太", "「太」象本源，多涉上古或正宗。"),
            ("魔", "「魔」象异道，多涉禁忌或外道。"),
            ("仙", "「仙」象超脱，多涉飞升或灵根。"),
            ("箓", "「箓」多见于符法、斋醮与度亡科仪系统。"),
            ("洞真", "「洞真」语出三洞，多贴上清一系经法语感。"),
            ("洞玄", "「洞玄」多贴近灵宝斋科、度人经教传统。"),
            ("洞神", "「洞神」多贴近三皇经文与召神役鬼一类。"),
            ("黄庭", "「黄庭」常与身神、内景存思修持相关。"),
            ("上清", "「上清」多为上清一系经箓之称。"),
            ("坛", "「坛」多与安坛、坛门、香火科仪相关。"),
            ("傩", "「傩」多与傩仪、面具、驱疫古俗相关。"),
            ("敕", "「敕」多与符敕、律令口吻相关。"),
            ("序列", "「序列」偏国漫式体系化命名语感。"),
            ("虚空", "「虚空」偏异界/界域类国漫修仙语感。"),
            ("权柄", "「权柄」偏权能、法则类国漫修仙语感。"),
            ("界律", "「界律」偏规则、禁制类国漫修仙语感。"),
        ]
        for ch, msg in triggers:
            if ch in name:
                return msg
        if name_type == "character":
            return "二字或三字结构，姓与名分拆组合，偏网文常用意象。"
        if name_type == "location":
            return "意象词与地理后缀组合，偏秘境、州郡、山水道场一类。"
        if name_type in ("skill", "item"):
            return "功法/器物名：数字、方位、材料与形制分层组合，避免纯随机单字堆叠。"
        return "东方玄幻风格命名。"

    low = name.lower()
    if style == "western":
        wtri = [
            ("shadow", "含 Shadow 语根，多与潜行、誓约或幽暗势力相关。"),
            ("star", "含 Star 语根，多与天象、预言或远行者相关。"),
            ("moon", "含 Moon 语根，多与潮汐、秘仪或夜行者相关。"),
            ("dragon", "含 Dragon 语根，多与古龙血脉或龙语遗产相关。"),
            ("rune", "含 Rune 语根，多与刻印、誓缚或古代铭文相关。"),
            ("blood", "含 Blood 语根，多与血誓、诅咒或战团相关。"),
            ("order", "含 Order 语根，多与骑士团、律法会相关。"),
            ("guild", "含 Guild 语根，多与行会、工匠或秘传组织相关。"),
        ]
        for kw, msg in wtri:
            if kw in low:
                return msg
    if style == "scifi":
        stri = [
            ("nova", "含 Nova 语根，多与恒星事件或跃迁航道相关。"),
            ("quant", "含 Quant 语根，多与量子协议或算力设施相关。"),
            ("ion", "含 Ion 语根，多与粒子束、推进或护盾相关。"),
            ("sector", "含 Sector 语根，多与星区行政或军事辖区相关。"),
            ("nebula", "含 Nebula 语根，多与星云矿带或深空驿站相关。"),
            ("hyper", "含 Hyper 语根，多与超光速或高能工程相关。"),
        ]
        for kw, msg in stri:
            if kw in low:
                return msg
    if style == "cthulhu":
        ctri = [
            ("rlyeh", "与拉莱耶音节相近，多涉沉没之城或梦境坐标。"),
            ("kadath", "与幻梦境冷原相关，多涉异界通路。"),
            ("arkham", "与密斯卡托尼克一带地名传统相关。"),
            ("carcosa", "与黄衣之王传说体系中的失名之城相关。"),
            ("cult", "含 Cult 语根，多与秘教、禁仪相关。"),
            ("elder", "含 Elder 语根，多与太古存在或禁忌知识相关。"),
        ]
        for kw, msg in ctri:
            if kw in low:
                return msg

    return _random_meaning(style, name_type)
