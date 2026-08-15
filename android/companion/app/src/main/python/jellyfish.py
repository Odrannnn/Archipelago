"""Small compatibility subset used by Archipelago option errors."""

def damerau_levenshtein_distance(left, right):
    left, right = str(left), str(right)
    table = {}
    for i in range(-1, len(left) + 1): table[(i, -1)] = i + 1
    for j in range(-1, len(right) + 1): table[(-1, j)] = j + 1
    for i, a in enumerate(left):
        for j, b in enumerate(right):
            cost = 0 if a == b else 1
            table[(i, j)] = min(table[(i - 1, j)] + 1, table[(i, j - 1)] + 1, table[(i - 1, j - 1)] + cost)
            if i and j and a == right[j - 1] and left[i - 1] == b:
                table[(i, j)] = min(table[(i, j)], table[(i - 2, j - 2)] + cost)
    return table[(len(left) - 1, len(right) - 1)]
