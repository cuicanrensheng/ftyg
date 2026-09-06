# -*- coding: utf-8 -*-
"""正确扫描所有 aar 的 classes.jar（解压 entry）中对 com.huya.security 的引用"""
import zipfile, io, os, re

AAR_DIR = "app/libs"
HITS = {}

for fn in os.listdir(AAR_DIR):
    if not fn.endswith(".aar"):
        continue
    aar_path = os.path.join(AAR_DIR, fn)
    try:
        with zipfile.ZipFile(aar_path) as aar:
            if "classes.jar" not in aar.namelist():
                continue
            cj = aar.read("classes.jar")
            with zipfile.ZipFile(io.BytesIO(cj)) as jar:
                for cls in jar.namelist():
                    if not cls.endswith(".class"):
                        continue
                    data = jar.read(cls)
                    if b"com/huya/security" in data or b"com.huya.security" in data:
                        # 提取常量池中的字符串
                        strings = set()
                        # 简单解析 UTF8 常量池项（class 文件格式：魔数4 + minor2 + major2 + cp_count2 + ...）
                        try:
                            cp_count = int.from_bytes(data[8:10], "big")
                            idx = 10
                            for i in range(1, cp_count):
                                tag = data[idx]
                                idx += 1
                                if tag == 1:  # UTF8
                                    ln = int.from_bytes(data[idx:idx+2], "big")
                                    idx += 2
                                    s = data[idx:idx+ln]
                                    try:
                                        strings.add(s.decode("utf-8"))
                                    except Exception:
                                        pass
                                    idx += ln
                                elif tag in (3, 4):  # int, float
                                    idx += 4
                                elif tag in (5, 6):  # long, double
                                    idx += 8
                                elif tag in (7, 8, 16, 19, 20):  # class, string, methodtype, module, package
                                    idx += 2
                                elif tag in (9, 10, 11, 12, 17, 18):  # field/method/interface/nameandtype/dynamic/invokedynamic
                                    idx += 4
                                elif tag == 15:  # methodhandle
                                    idx += 3
                                else:
                                    break
                        except Exception:
                            pass
                        sec = [s for s in strings if "huya/security" in s or "huya.security" in s]
                        if sec:
                            HITS.setdefault(fn, []).append((cls, sorted(sec)))
    except Exception as e:
        print(f"ERR {fn}: {e}")

for aar, hits in HITS.items():
    print(f"===== {aar} =====")
    for cls, sec in hits:
        print(f"  {cls}")
        for s in sec:
            print(f"      {s}")
