#!/bin/bash
# Deploy script para VPS Fanta Photobooth
# Execute no servidor VPS como root:
# ssh root@72.62.9.136 < DEPLOY_SCRIPT.sh

set -e

echo "=== Fanta Photobooth Deploy Script ==="
echo "Data: $(date)"

# 1. Verificar se está no diretório correto
PROJECT_DIR="/root/fanta-filtro"  # Ajuste se necessário
if [ ! -d "$PROJECT_DIR" ]; then
    echo "❌ Erro: $PROJECT_DIR não encontrado"
    exit 1
fi

cd "$PROJECT_DIR"
echo "✅ Diretório do projeto: $(pwd)"

# 2. Fazer backup da HTML atual
BACKUP_DIR="backups"
mkdir -p "$BACKUP_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
cp templates/index.html "$BACKUP_DIR/index.html.bak.$TIMESTAMP"
echo "✅ Backup feito: $BACKUP_DIR/index.html.bak.$TIMESTAMP"

# 3. Atualizar o código (puxar mudanças do git)
echo "Atualizando código..."
git pull origin main || echo "⚠️  Git pull falhou, continuando..."

# 4. Reconstruir e reiniciar Docker
echo "Reconstruindo container Docker..."
docker-compose -f docker-compose.filtro.yml down
docker-compose -f docker-compose.filtro.yml build --no-cache
docker-compose -f docker-compose.filtro.yml up -d

# 5. Verificar se está rodando
sleep 3
if docker ps | grep -q "filtro_fanta_web"; then
    echo "✅ Container filtro_fanta_web está rodando"
else
    echo "❌ Erro: Container não está rodando"
    exit 1
fi

# 6. Verificar saúde da aplicação
echo "Verificando saúde da aplicação..."
curl -s http://localhost:5000/ > /dev/null && echo "✅ HTTP 200 OK" || echo "⚠️  Aplicação não respondeu"

# 7. Ver logs
echo ""
echo "=== Logs dos últimos 10 segundos ==="
docker logs --tail 50 filtro_fanta_web | tail -20

echo ""
echo "✅ Deploy completo!"
echo "URL: https://fanta-filtro.seuprojeto.online/"
