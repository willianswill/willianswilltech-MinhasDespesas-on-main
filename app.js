/**
 * Minhas Despesas - Gestão Financeira Inteligente & Meta Diária
 */

const STORAGE_KEY = 'minhas_despesas_dados_v1';
const SETTINGS_KEY = 'minhas_despesas_config_v1';

// Dados de Exemplo para inicialização suave
const SAMPLE_EXPENSES = [
  { id: '1', name: 'Aluguel do Imóvel', value: 1450.00, day: 10, category: 'Moradia', type: 'Fixa', status: 'paid', obs: 'PIX proprietário' },
  { id: '2', name: 'Condomínio', value: 380.00, day: 15, category: 'Moradia', type: 'Fixa', status: 'pending', obs: 'Boleto bancário' },
  { id: '3', name: 'Energia Elétrica (Enel)', value: 195.40, day: 18, category: 'Contas', type: 'Recorrente', status: 'pending', obs: 'Débito automático' },
  { id: '4', name: 'Internet Fibra', value: 119.90, day: 20, category: 'Contas', type: 'Fixa', status: 'pending', obs: '500 Mega' },
  { id: '5', name: 'Supermercado (Mensal)', value: 1200.00, day: 5, category: 'Alimentação', type: 'Recorrente', status: 'paid', obs: 'Compras do mês' },
  { id: '6', name: 'Combustível / Transporte', value: 450.00, day: 25, category: 'Transporte', type: 'Recorrente', status: 'pending', obs: 'Estimativa mensal' },
  { id: '7', name: 'Plano de Saúde', value: 320.00, day: 12, category: 'Saúde', type: 'Fixa', status: 'paid', obs: 'Individual' },
  { id: '8', name: 'Cartão de Crédito', value: 980.50, day: 28, category: 'Cartão/Empréstimo', type: 'Recorrente', status: 'pending', obs: 'Fatura fechada' }
];

// Estado da Aplicação
let expenses = [];
let daysCalculationMode = 'all'; // 'all', 'business', 'business_sat'

// Elementos DOM
const currentDateBadge = document.getElementById('current-date-badge');
const dailyTargetValue = document.getElementById('daily-target-value');
const targetExplanation = document.getElementById('target-explanation');
const daysModeSelect = document.getElementById('days-mode-select');
const daysLeftCount = document.getElementById('days-left-count');
const daysLeftLabel = document.getElementById('days-left-label');

const kpiTotal = document.getElementById('kpi-total');
const kpiCount = document.getElementById('kpi-count');
const kpiPaid = document.getElementById('kpi-paid');
const kpiPaidPercent = document.getElementById('kpi-paid-percent');
const kpiPending = document.getElementById('kpi-pending');
const kpiPendingSub = document.getElementById('kpi-pending-sub');

const progressBarFill = document.getElementById('progress-bar-fill');
const progressPercentageLabel = document.getElementById('progress-percentage-label');

const filterSearch = document.getElementById('filter-search');
const filterStatus = document.getElementById('filter-status');
const filterCategory = document.getElementById('filter-category');
const filterType = document.getElementById('filter-type');
const sortOrder = document.getElementById('sort-order');

const expensesTbody = document.getElementById('expenses-tbody');
const emptyState = document.getElementById('empty-state');
const visibleCount = document.getElementById('visible-count');

// Modal Elements
const expenseModal = document.getElementById('expense-modal');
const expenseForm = document.getElementById('expense-form');
const modalTitle = document.getElementById('modal-title');
const modalCloseBtn = document.getElementById('modal-close-btn');
const btnCancelModal = document.getElementById('btn-cancel-modal');
const btnOpenModal = document.getElementById('btn-open-modal');

const formId = document.getElementById('form-id');
const formName = document.getElementById('form-name');
const formValue = document.getElementById('form-value');
const formDay = document.getElementById('form-day');
const formCategory = document.getElementById('form-category');
const formType = document.getElementById('form-type');
const formStatus = document.getElementById('form-status');
const formObs = document.getElementById('form-obs');

const btnExport = document.getElementById('btn-export');
const btnQuickSample = document.getElementById('btn-quick-sample');
const btnClearAll = document.getElementById('btn-clear-all');

/* =========================================================
   INICIALIZAÇÃO
   ========================================================= */
function init() {
  loadData();
  renderDate();
  attachEvents();
  render();
}

function loadData() {
  const savedExpenses = localStorage.getItem(STORAGE_KEY);
  if (savedExpenses) {
    try {
      expenses = JSON.parse(savedExpenses);
    } catch (e) {
      expenses = [...SAMPLE_EXPENSES];
    }
  } else {
    // Primeira vez: carrega dados de exemplo para facilitar visualização
    expenses = [...SAMPLE_EXPENSES];
    saveData();
  }

  const savedSettings = localStorage.getItem(SETTINGS_KEY);
  if (savedSettings) {
    try {
      const cfg = JSON.parse(savedSettings);
      daysCalculationMode = cfg.daysCalculationMode || 'all';
      daysModeSelect.value = daysCalculationMode;
    } catch (e) {}
  }
}

function saveData() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(expenses));
}

function saveSettings() {
  localStorage.setItem(SETTINGS_KEY, JSON.stringify({ daysCalculationMode }));
}

function renderDate() {
  const now = new Date();
  const options = { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' };
  const formatted = now.toLocaleDateString('pt-BR', options);
  currentDateBadge.textContent = formatted.charAt(0).toUpperCase() + formatted.slice(1);
}

/* =========================================================
   CÁLCULOS MATEMÁTICOS & META DIÁRIA
   ========================================================= */
function formatCurrency(val) {
  return Number(val || 0).toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/**
 * Calcula a quantidade de dias restantes no mês corrente a partir de hoje
 */
function getRemainingDaysInMonth(mode = 'all') {
  const now = new Date();
  const year = now.getFullYear();
  const month = now.getMonth();
  const currentDay = now.getDate();

  // Último dia do mês
  const lastDayDate = new Date(year, month + 1, 0);
  const totalDaysInMonth = lastDayDate.getDate();

  let count = 0;
  for (let d = currentDay; d <= totalDaysInMonth; d++) {
    const dayDate = new Date(year, month, d);
    const dayOfWeek = dayDate.getDay(); // 0 = Domingo, 6 = Sábado

    if (mode === 'all') {
      count++;
    } else if (mode === 'business') {
      // Segunda a Sexta (1 a 5)
      if (dayOfWeek !== 0 && dayOfWeek !== 6) {
        count++;
      }
    } else if (mode === 'business_sat') {
      // Segunda a Sábado (1 a 6, exceto Domingo)
      if (dayOfWeek !== 0) {
        count++;
      }
    }
  }

  return {
    remainingDays: Math.max(count, 1), // Mínimo 1 para evitar divisão por zero
    currentDay,
    totalDaysInMonth
  };
}

function calculateMetrics() {
  const total = expenses.reduce((acc, exp) => acc + (Number(exp.value) || 0), 0);
  const paid = expenses
    .filter(exp => exp.status === 'paid')
    .reduce((acc, exp) => acc + (Number(exp.value) || 0), 0);
  const pending = total - paid;

  const paidPercent = total > 0 ? Math.round((paid / total) * 100) : 0;

  const { remainingDays, currentDay, totalDaysInMonth } = getRemainingDaysInMonth(daysCalculationMode);

  // Meta diária necessária para pagar as dívidas que restam no mês
  const dailyTarget = pending > 0 ? pending / remainingDays : 0;

  return {
    total,
    paid,
    pending,
    paidPercent,
    dailyTarget,
    remainingDays,
    currentDay,
    totalDaysInMonth
  };
}

/* =========================================================
   RENDERIZAÇÃO
   ========================================================= */
function render() {
  const metrics = calculateMetrics();

  // Atualizar Target Banner
  dailyTargetValue.textContent = formatCurrency(metrics.dailyTarget);
  daysLeftCount.textContent = metrics.remainingDays;

  let modeDesc = 'dias corridos restantes';
  if (daysCalculationMode === 'business') modeDesc = 'dias úteis (seg a sex)';
  if (daysCalculationMode === 'business_sat') modeDesc = 'dias úteis + sábados';
  daysLeftLabel.textContent = `${modeDesc} no mês`;

  if (metrics.pending <= 0 && metrics.total > 0) {
    targetExplanation.innerHTML = `🎉 <strong>Parabéns!</strong> Todas as suas despesas deste mês já foram quitadas!`;
  } else if (metrics.total === 0) {
    targetExplanation.textContent = `Cadastre suas despesas fixas e recorrentes para calcular sua meta diária.`;
  } else {
    targetExplanation.innerHTML = `Faltam <strong>R$ ${formatCurrency(metrics.pending)}</strong> para quitar o mês. Dividindo pelos <strong>${metrics.remainingDays} ${modeDesc}</strong> restantes, você precisa produzir <strong>R$ ${formatCurrency(metrics.dailyTarget)} por dia</strong>.`;
  }

  // Atualizar KPIs
  kpiTotal.textContent = `R$ ${formatCurrency(metrics.total)}`;
  kpiCount.textContent = `${expenses.length} despesa${expenses.length === 1 ? '' : 's'} cadastrada${expenses.length === 1 ? '' : 's'}`;

  kpiPaid.textContent = `R$ ${formatCurrency(metrics.paid)}`;
  kpiPaidPercent.textContent = `${metrics.paidPercent}% do total do mês quitado`;

  kpiPending.textContent = `R$ ${formatCurrency(metrics.pending)}`;
  kpiPendingSub.textContent = `Resta quitar R$ ${formatCurrency(metrics.pending)}`;

  // Barra de progresso
  progressBarFill.style.width = `${metrics.paidPercent}%`;
  progressPercentageLabel.textContent = `${metrics.paidPercent}% quitado (${expenses.filter(e => e.status === 'paid').length}/${expenses.length})`;

  // Renderizar Lista Filtrada
  renderTable();
}

function getFilteredExpenses() {
  const search = filterSearch.value.trim().toLowerCase();
  const status = filterStatus.value;
  const category = filterCategory.value;
  const type = filterType.value;
  const sort = sortOrder.value;

  let filtered = expenses.filter(exp => {
    // Filtro Busca
    if (search) {
      const matchName = exp.name.toLowerCase().includes(search);
      const matchObs = exp.obs && exp.obs.toLowerCase().includes(search);
      const matchCat = exp.category.toLowerCase().includes(search);
      if (!matchName && !matchObs && !matchCat) return false;
    }

    // Filtro Status
    if (status !== 'all') {
      if (exp.status !== status) return false;
    }

    // Filtro Categoria
    if (category !== 'all') {
      if (exp.category !== category) return false;
    }

    // Filtro Tipo
    if (type !== 'all') {
      if (exp.type !== type) return false;
    }

    return true;
  });

  // Ordenação
  const today = new Date().getDate();

  filtered.sort((a, b) => {
    switch (sort) {
      case 'due_asc':
        return a.day - b.day;
      case 'due_desc':
        return b.day - a.day;
      case 'val_desc':
        return b.value - a.value;
      case 'val_asc':
        return a.value - b.value;
      case 'name_asc':
        return a.name.localeCompare(b.name);
      default:
        return 0;
    }
  });

  return filtered;
}

function renderTable() {
  const filtered = getFilteredExpenses();
  visibleCount.textContent = filtered.length;
  expensesTbody.innerHTML = '';

  if (filtered.length === 0) {
    emptyState.style.display = 'block';
    return;
  } else {
    emptyState.style.display = 'none';
  }

  const currentDay = new Date().getDate();

  filtered.forEach(exp => {
    const isPaid = exp.status === 'paid';
    const tr = document.createElement('tr');
    if (isPaid) tr.classList.add('tr-paid');

    // Status vencimento
    let dueStatusHtml = '';
    if (isPaid) {
      dueStatusHtml = `<span class="due-status text-green">Pago</span>`;
    } else if (exp.day < currentDay) {
      dueStatusHtml = `<span class="due-status due-overdue">Venceu dia ${exp.day}</span>`;
    } else if (exp.day === currentDay) {
      dueStatusHtml = `<span class="due-status due-today">Vence HOJE!</span>`;
    } else {
      const daysDiff = exp.day - currentDay;
      dueStatusHtml = `<span class="due-status due-future">Em ${daysDiff} dia${daysDiff === 1 ? '' : 's'}</span>`;
    }

    tr.innerHTML = `
      <td>
        <button class="status-btn ${isPaid ? 'is-paid' : ''}" onclick="toggleExpenseStatus('${exp.id}')" title="${isPaid ? 'Marcar como pendente' : 'Marcar como paga'}">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
            <polyline points="20 6 9 17 4 12"></polyline>
          </svg>
        </button>
      </td>
      <td>
        <span class="desc-title">${escapeHtml(exp.name)}</span>
        ${exp.obs ? `<span class="desc-obs">${escapeHtml(exp.obs)}</span>` : ''}
      </td>
      <td>
        <span class="badge badge-cat">${exp.category}</span>
      </td>
      <td>
        <span class="badge badge-type">${exp.type}</span>
      </td>
      <td>
        <div class="due-info">
          <span class="due-day">Dia ${exp.day}</span>
          ${dueStatusHtml}
        </div>
      </td>
      <td>
        <span class="amount-text">R$ ${formatCurrency(exp.value)}</span>
      </td>
      <td>
        <div class="row-actions">
          <button class="btn-icon" onclick="editExpense('${exp.id}')" title="Editar">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
            </svg>
          </button>
          <button class="btn-icon danger" onclick="deleteExpense('${exp.id}')" title="Excluir">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="3 6 5 6 21 6"></polyline>
              <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
            </svg>
          </button>
        </div>
      </td>
    `;
    expensesTbody.appendChild(tr);
  });
}

function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/[&<>'"]/g, 
    tag => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      "'": '&#39;',
      '"': '&quot;'
    }[tag] || tag)
  );
}

/* =========================================================
   AÇÕES & MODAL
   ========================================================= */
window.openExpenseModal = function(id = null) {
  expenseForm.reset();
  if (id) {
    const item = expenses.find(e => e.id === id);
    if (!item) return;
    modalTitle.textContent = 'Editar Despesa';
    formId.value = item.id;
    formName.value = item.name;
    formValue.value = item.value;
    formDay.value = item.day;
    formCategory.value = item.category;
    formType.value = item.type;
    formStatus.value = item.status;
    formObs.value = item.obs || '';
  } else {
    modalTitle.textContent = 'Nova Despesa';
    formId.value = '';
    formDay.value = new Date().getDate();
    formCategory.value = 'Moradia';
    formType.value = 'Fixa';
    formStatus.value = 'pending';
  }
  expenseModal.classList.add('active');
  formName.focus();
};

window.closeExpenseModal = function() {
  expenseModal.classList.remove('active');
};

window.toggleExpenseStatus = function(id) {
  const item = expenses.find(e => e.id === id);
  if (item) {
    item.status = item.status === 'paid' ? 'pending' : 'paid';
    saveData();
    render();
  }
};

window.editExpense = function(id) {
  openExpenseModal(id);
};

window.deleteExpense = function(id) {
  const item = expenses.find(e => e.id === id);
  if (!item) return;
  if (confirm(`Deseja realmente excluir "${item.name}"?`)) {
    expenses = expenses.filter(e => e.id !== id);
    saveData();
    render();
  }
};

/* =========================================================
   EVENT LISTENERS
   ========================================================= */
function attachEvents() {
  // Alteração no modo de cálculo de dias
  daysModeSelect.addEventListener('change', (e) => {
    daysCalculationMode = e.target.value;
    saveSettings();
    render();
  });

  // Filtros
  filterSearch.addEventListener('input', renderTable);
  filterStatus.addEventListener('change', renderTable);
  filterCategory.addEventListener('change', renderTable);
  filterType.addEventListener('change', renderTable);
  sortOrder.addEventListener('change', renderTable);

  // Modal botões
  btnOpenModal.addEventListener('click', () => openExpenseModal());
  modalCloseBtn.addEventListener('click', closeExpenseModal);
  btnCancelModal.addEventListener('click', closeExpenseModal);

  expenseModal.addEventListener('click', (e) => {
    if (e.target === expenseModal) closeExpenseModal();
  });

  // Salvar formulário
  expenseForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const id = formId.value;
    const name = formName.value.trim();
    const value = parseFloat(formValue.value);
    const day = parseInt(formDay.value, 10);
    const category = formCategory.value;
    const type = formType.value;
    const status = formStatus.value;
    const obs = formObs.value.trim();

    if (!name || isNaN(value) || isNaN(day)) {
      alert('Por favor, preencha os campos obrigatórios corretamente.');
      return;
    }

    if (id) {
      // Atualizar existente
      const index = expenses.findIndex(exp => exp.id === id);
      if (index !== -1) {
        expenses[index] = { id, name, value, day, category, type, status, obs };
      }
    } else {
      // Criar nova
      const newExpense = {
        id: Date.now().toString(),
        name,
        value,
        day,
        category,
        type,
        status,
        obs
      };
      expenses.push(newExpense);
    }

    saveData();
    closeExpenseModal();
    render();
  });

  // Inserir amostras
  btnQuickSample.addEventListener('click', () => {
    if (confirm('Deseja recarregar a lista com os exemplos de despesas pré-definidos?')) {
      expenses = [...SAMPLE_EXPENSES];
      saveData();
      render();
    }
  });

  // Limpar tudo
  btnClearAll.addEventListener('click', () => {
    if (confirm('ATENÇÃO: Deseja apagar todas as despesas cadastradas?')) {
      expenses = [];
      saveData();
      render();
    }
  });

  // Exportar Backup
  btnExport.addEventListener('click', () => {
    const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(expenses, null, 2));
    const downloadAnchor = document.createElement('a');
    downloadAnchor.setAttribute("href", dataStr);
    downloadAnchor.setAttribute("download", `backup_minhas_despesas_${new Date().toISOString().slice(0, 10)}.json`);
    document.body.appendChild(downloadAnchor);
    downloadAnchor.click();
    downloadAnchor.remove();
  });
}

// Iniciar ao carregar a página
document.addEventListener('DOMContentLoaded', init);
