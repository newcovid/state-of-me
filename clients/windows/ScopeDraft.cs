namespace StateOfMe;

/// <summary>A draft always retains the revision it was edited against, even after another audience refreshes.</summary>
public sealed record ScopeDraft(HashSet<string> Fields, long Revision)
{
    public static ScopeDraft? Capture(IEnumerable<string> baseline, IEnumerable<string> selected, long revision)
    {
        var fields = selected.ToHashSet(StringComparer.Ordinal);
        return fields.SetEquals(baseline) ? null : new ScopeDraft(fields, revision);
    }
}
