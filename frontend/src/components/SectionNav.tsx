interface SectionNavProps<T extends string> {
  label: string
  sections: ReadonlyArray<{ id: T; title: string }>
  active: T
  onSelect: (section: T) => void
}

/** Second-level navigation inside one workbench step, so adding screens does not widen the top bar. */
export function SectionNav<T extends string>({ label, sections, active, onSelect }: SectionNavProps<T>) {
  return (
    <nav className="section-nav" aria-label={label}>
      {sections.map((section) => (
        <button
          key={section.id}
          type="button"
          aria-current={section.id === active ? 'page' : undefined}
          className={section.id === active ? 'active' : undefined}
          onClick={() => onSelect(section.id)}
        >
          {section.title}
        </button>
      ))}
    </nav>
  )
}
