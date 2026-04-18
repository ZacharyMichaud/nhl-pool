import {
  Component,
  ElementRef,
  EventEmitter,
  HostBinding,
  Input,
  OnDestroy,
  OnInit,
  Output,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { DropdownOption } from './dropdown.types';

@Component({
  selector: 'app-dropdown',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './dropdown.component.html',
  styleUrl: './dropdown.component.scss',
})
export class DropdownComponent implements OnInit, OnDestroy {
  @Input() options: DropdownOption[] = [];
  @Input() value: any = null;   // single: any scalar | multiple: any[]
  @Output() valueChange = new EventEmitter<any>();
  @Input() placeholder = 'Select…';
  @Input() multiple = false;    // enable multi-select mode
  @Input() disabled = false;    // disable interaction

  @HostBinding('class.disabled') get isDisabled() { return this.disabled; }

  open = false;

  /** Fixed-position coordinates for the panel (escapes overflow:hidden ancestors) */
  panelStyle: Record<string, string> = {};

  // ── Single-select helpers ─────────────────

  get selectedOption(): DropdownOption | undefined {
    if (this.multiple) return undefined;
    return this.options.find(o => o.value === this.value);
  }

  // ── Multi-select helpers ──────────────────

  get selectedValues(): any[] {
    return Array.isArray(this.value) ? this.value : [];
  }

  /** Label shown in the trigger button */
  get triggerLabel(): string {
    if (!this.multiple) {
      return this.selectedOption?.label ?? '';
    }
    const arr = this.selectedValues;
    if (arr.length === 0) return '';
    if (arr.length === 1) return this.options.find(o => o.value === arr[0])?.label ?? '';
    return `${arr.length} selected`;
  }

  isSelected(option: DropdownOption): boolean {
    if (this.multiple) return this.selectedValues.includes(option.value);
    return this.value === option.value;
  }

  // ─────────────────────────────────────────

  constructor(private el: ElementRef) {}

  private outsideClickHandler = (e: MouseEvent) => {
    if (!this.el.nativeElement.contains(e.target)) {
      this.open = false;
    }
  };

  /** Recalculate panel position — called on open, scroll, and resize */
  private repositionPanel() {
    const trigger = this.el.nativeElement.querySelector('.dropdown-trigger') as HTMLElement;
    if (!trigger) return;
    const rect = trigger.getBoundingClientRect();
    const spaceBelow = window.innerHeight - rect.bottom;
    const panelHeight = Math.min(this.options.length * 44 + 8, 300);
    if (spaceBelow >= panelHeight || spaceBelow >= 120) {
      this.panelStyle = {
        position: 'fixed',
        top: rect.bottom + 5 + 'px',
        left: rect.left + 'px',
        width: rect.width + 'px',
        zIndex: '9999',
      };
    } else {
      this.panelStyle = {
        position: 'fixed',
        bottom: (window.innerHeight - rect.top + 5) + 'px',
        left: rect.left + 'px',
        width: rect.width + 'px',
        zIndex: '9999',
      };
    }
  }

  private scrollResizeHandler = () => {
    if (this.open) {
      this.repositionPanel();
    }
  };

  ngOnInit() {
    document.addEventListener('click', this.outsideClickHandler, true);
    window.addEventListener('scroll', this.scrollResizeHandler, true);
    window.addEventListener('resize', this.scrollResizeHandler);
  }

  ngOnDestroy() {
    document.removeEventListener('click', this.outsideClickHandler, true);
    window.removeEventListener('scroll', this.scrollResizeHandler, true);
    window.removeEventListener('resize', this.scrollResizeHandler);
  }

  select(option: DropdownOption) {
    if (this.disabled) return;
    if (this.multiple) {
      const arr = [...this.selectedValues];
      const idx = arr.indexOf(option.value);
      if (idx > -1) arr.splice(idx, 1);
      else arr.push(option.value);
      this.value = arr;
      this.valueChange.emit(arr);
      // keep panel open for multi-select
    } else {
      this.value = option.value;
      this.valueChange.emit(option.value);
      this.open = false;
    }
  }

  toggle() {
    if (this.disabled) return;
    if (!this.open) {
      this.repositionPanel();
    }
    this.open = !this.open;
  }
}
