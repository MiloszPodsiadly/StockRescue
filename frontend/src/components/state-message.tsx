import {
  ArrowLeftRight, LoaderCircle, PackageOpen, ShieldAlert, TriangleAlert, WifiOff,
} from 'lucide-react'

const icons = {
  loading: LoaderCircle,
  error: TriangleAlert,
  empty: PackageOpen,
  unavailable: WifiOff,
  forbidden: ShieldAlert,
  conflict: ArrowLeftRight,
}

interface StateMessageProps {
  state: keyof typeof icons
  title: string
  description: string
}

export function StateMessage({ state, title, description }: StateMessageProps) {
  const Icon = icons[state]
  return (
    <div role={state === 'error' || state === 'unavailable' ? 'alert' : 'status'}>
      <Icon aria-hidden="true" className={`mb-4 size-7 ${state === 'loading' ? 'animate-spin' : ''}`} />
      <h3 className="text-lg font-semibold tracking-tight">{title}</h3>
      <p className="mt-2 max-w-prose text-sm leading-relaxed text-muted-foreground">{description}</p>
    </div>
  )
}
