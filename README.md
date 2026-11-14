# 💰 Minhas Despesas (willianswilltech)

> Aplicativo moderno para controle de despesas fixas e recorrentes mensais, com filtros avançados e cálculo automático da **meta diária de faturamento necessária para quitar as contas do mês**, atualizado dia a dia.

---

## 🚀 Principais Funcionalidades

1. **Cálculo da Meta Diária (Atualização Dia a Dia):**
   - Calcula automaticamente quanto você precisa faturar/ganhar por dia para quitar todas as despesas restantes do mês corrente.
   - Opções flexíveis para o cálculo:
     - **Todos os dias restantes (Corridos)**.
     - **Apenas dias úteis (Segunda a Sexta)**.
     - **Dias úteis + Sábados**.
   - Atualiza a cada dia que passa no mês e conforme você marca contas como pagas.

2. **Cadastro Completo de Despesas:**
   - Nome / Descrição.
   - Valor (R$).
   - Dia de vencimento no mês (com alertas visuais de contas vencidas, contas do dia e próximas).
   - Categorias (Moradia, Contas, Alimentação, Transporte, Saúde, Educação, Assinaturas, Lazer, etc.).
   - Tipos de despesa: **Fixa**, **Recorrente** ou **Variável**.
   - Observações e notas adicionais.

3. **Múltiplos Filtros e Busca em Tempo Real:**
   - Busca textual instantânea por nome ou observações.
   - Filtro por **Status** (Todas, Apenas Pendentes, Apenas Pagas).
   - Filtro por **Categoria**.
   - Filtro por **Tipo** (Fixa, Recorrente, Variável).
   - Ordenação dinâmica (Vencimento mais próximo, maior valor, menor valor, ordem alfabética).

4. **Painel de Indicadores (KPIs):**
   - Total de despesas do mês.
   - Total já quitado (com porcentagem concluída).
   - Total restante pendente.
   - Barra de progresso visual de quitação do mês.

5. **Privacidade e Persistência:**
   - Funciona 100% no seu navegador, sem necessidade de servidores externos.
   - Dados salvos localmente via `localStorage`.
   - Botão para **Exportar Backup** em arquivo JSON.

---

## 💻 Como Abrir e Usar

1. Basta dar dois cliques no arquivo `index.html` para abrir diretamente no seu navegador padrão (Google Chrome, Microsoft Edge, Firefox, Brave, etc.).
2. Ou use qualquer servidor web local simples (como Live Server no VS Code ou Python `python -m http.server`).

---

## 🛠️ Tecnologias Utilizadas

- **HTML5** semântico.
- **CSS3 Moderno** com tema Dark sofisticado, CSS Grid, Flexbox e animações fluidas.
- **JavaScript Puro (Vanilla JS)**: sem dependências externas, leve e rápido.
