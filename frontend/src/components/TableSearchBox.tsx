interface TableSearchBoxProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
}

export function TableSearchBox({
  value,
  onChange,
  placeholder = "Search all columns… (or column=value, e.g. channel=6)",
}: TableSearchBoxProps) {
  return (
    <input
      type="search"
      className="table-search"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
    />
  );
}
