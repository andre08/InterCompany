package br.com.ventisol.intercompany;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import br.com.sankhya.extensions.actionbutton.AcaoRotinaJava;
import br.com.sankhya.extensions.actionbutton.ContextoAcao;
import br.com.sankhya.extensions.actionbutton.Registro;
import br.com.sankhya.jape.EntityFacade;
import br.com.sankhya.jape.bmp.PersistentLocalEntity;
import br.com.sankhya.jape.core.JapeSession;
import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.jape.sql.NativeSql;
import br.com.sankhya.jape.util.JapeSessionContext;
import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.jape.vo.EntityVO;
import br.com.sankhya.modelcore.comercial.BarramentoRegra;
import br.com.sankhya.modelcore.comercial.BarramentoRegra.DadosBarramento;
import br.com.sankhya.modelcore.comercial.CentralFinanceiro;
import br.com.sankhya.modelcore.comercial.CentralItemNota;
import br.com.sankhya.modelcore.comercial.CentralItemNota.ParamsInicializacaoProduto;
import br.com.sankhya.modelcore.comercial.ComercialUtils;
import br.com.sankhya.modelcore.comercial.ComercialUtils.PrecoUnitarioInfo;
import br.com.sankhya.modelcore.comercial.ConfirmacaoNotaHelper;
import br.com.sankhya.modelcore.comercial.impostos.ImpostosHelpper;
import br.com.sankhya.modelcore.dwfdata.vo.tsi.UsuarioVO;
import br.com.sankhya.modelcore.util.DynamicEntityNames;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;
import com.sankhya.util.BigDecimalUtil;
import static java.lang.String.*;
import static java.math.BigDecimal.*;

/**
 *
 * @author andre
 */
public class ExpodePedidoInterCompany implements AcaoRotinaJava {

    EntityFacade dwFacade = EntityFacadeFactory.getDWFFacade();
    JapeSession.SessionHandle hnd = null;

    @Override
    public void doAction(ContextoAcao contexto) throws Exception {

        JdbcWrapper jdbc = null;
        String observacao;
        BigDecimal nunotaAjusteSaida = null; //Ajuste de estoque para saida dos produto
        BigDecimal nunotaAjusteEntrada = null; //Ajuste de estoque para entrada das pecas
        BigDecimal nunotaPedidoNacional = null; //Pedido de Destino com pecas nacional
        BigDecimal nunotaPedidoImportado = null; //Pedido de Destino com pecas importadas

        try {

            jdbc = dwFacade.getJdbcWrapper();
            NativeSql qryPEDIDO = new NativeSql(jdbc);
            int qtdeRS = 0;

            for (Registro registro : contexto.getLinhas()) {

                // Pegando o paramentro da tela
                BigDecimal nunota = (BigDecimal) registro.getCampo("NUNOTA");

                //verificando se o pedido já foi desmembrado
                qryPEDIDO.setNamedParameter("NUNOTA", nunota);
                ResultSet rsPEDIDO = qryPEDIDO.executeQuery("SELECT COUNT(*) AS QTDE FROM AD_PEDINTERCOMPANY WHERE NUNOTA = :NUNOTA AND (NUNOTAAJUSTESAIDA IS NOT NULL OR NUNOTAAJUSTEENTRADA IS NOT NULL OR NUNOTAPEDIDONACIONAL IS NOT NULL OR NUNOTAPEDIDOIMPORTADO IS NOT NULL)");

                qtdeRS = 0;
                while (rsPEDIDO.next()) {
                    qtdeRS = rsPEDIDO.getInt("QTDE");
                }

                if (qtdeRS >= 1) {
                    throw new Exception("O registro " + nunota + " não pode ser processado, pois já existe processo em andamento");
                }

                rsPEDIDO.close();

                //Validacao sobre geracao do pedido
                if (contexto.confirmarSimNao("Explode novo pedido INTERCOMPANY", "Gerar novo pedido para o lancamento de Nro. " + nunota, 1)) {

//                    //incluindo pedido no processo 
//                    try {
//                        jdbc.openSession();
//                        CallableStatement rsProc = jdbc.getConnection().prepareCall("{CALL AD_STP_PEDINTERCOMPANY_ADIC(?)}");
//                        rsProc.setBigDecimal(1, nunota);
//                        rsProc.execute();
//                        rsProc.close();
//                        jdbc.closeSession();
//                    } catch (Exception e) {
//                        throw new Exception("Erro:  " + e.getMessage());
//                    }
//                    
                    //Atualizando o pedido para verificar se tem alguma pendencia (cadastro de preço e quatidade não-negativa no estoque)
                    try {
                        jdbc.openSession();
                        CallableStatement rsProc = jdbc.getConnection().prepareCall("{CALL AD_STP_PEDINTERCOMPANY(?)}");
                        rsProc.setBigDecimal(1, nunota);
                        rsProc.execute();
                        rsProc.close();
                        jdbc.closeSession();
                    } catch (Exception e) {
                        throw new Exception("Erro:  " + e.getMessage());
                    }
                    
                    
                    //VALIDANDO PRECO E ESTOQUE
                    qryPEDIDO.setNamedParameter("NUNOTA", nunota);
                    rsPEDIDO = qryPEDIDO.executeQuery("select codmatprima AS CODPROD from ad_pedintercompanypart WHERE NUNOTA = :NUNOTA AND nvl(MENSAGEM, 'erro') <> 'OK' UNION select CODPROD from ad_pedintercompanyprod WHERE NUNOTA = :NUNOTA AND MENSAGEM <> 'OK'");

                    qtdeRS = 0;
                    while (rsPEDIDO.next()) {
                        qtdeRS++;
                    }

                    if (qtdeRS >= 1) {
                        throw new Exception("O registro " + nunota + " não pode ser processado, o pedido tem " + qtdeRS + " itens para verificação. Verifique as mensagem na tela de 'Explode pedido Intercompany'");
                    }

                    rsPEDIDO.close();
                    
                    //fazendo um backup do pedido de original para o processo de refazer o pedido original
                    try {
                        jdbc.openSession();
                        CallableStatement rsProc = jdbc.getConnection().prepareCall("{CALL AD_STP_PEDINTERCOMPANY_PROC(?)}");
                        rsProc.setBigDecimal(1, nunota);
                        rsProc.execute();
                        rsProc.close();
                        jdbc.closeSession();
                    } catch (Exception e) {
                        throw new Exception("Erro:  " + e.getMessage());
                    }

                    //removendo pedido original de produtos para nordeste                                        
                    try {
                        
                        excluirPedido(nunota, jdbc);
    
                    } catch (Exception e) {
                        throw new Exception("Erro:  " + e.getMessage());
                    }   

                    //Inserindo ajuste de estoque de saida para os produtos do pedido com base no pedido origem
                    //DynamicVO modeloCABSaida = buscaModelo(BigDecimal.valueOf(655718)); // ambiente de teste
                    DynamicVO modeloCABSaida = buscaModelo(BigDecimal.valueOf(2519601)); // ambiente de producao
                    observacao = "Pedido de ajuste para consumir os produtos que serão enviado para Nordeste. Pedido de origem " + nunota;
                    DynamicVO cabVOSaida = inserirCabecalho(modeloCABSaida, observacao, BigDecimal.valueOf(397));
                    nunotaAjusteSaida = cabVOSaida.asBigDecimal("NUNOTA");
                    
                    qryPEDIDO.cleanParameters();
                    qryPEDIDO.setNamedParameter("NUNOTA", nunota);
                    rsPEDIDO = qryPEDIDO.executeQuery("SELECT CODPROD, QTDNEG, CODLOCALORIG, CODVOL, SEQUENCIA FROM AD_PEDINTERCOMPANYPROD WHERE NUNOTA = :NUNOTA ORDER BY SEQUENCIA");
                    //Inserindo os itens no pedido novo
                    while (rsPEDIDO.next()) {
                        inseriItem(cabVOSaida, rsPEDIDO.getBigDecimal("CODPROD"), rsPEDIDO.getBigDecimal("QTDNEG"), rsPEDIDO.getBigDecimal("CODLOCALORIG"), rsPEDIDO.getString("CODVOL"));
                    }
                    rsPEDIDO.close();

                    //ConfirmacaoNotaHelper confirmacaoNota = new ConfirmacaoNotaHelper();
                    //ConfirmacaoNotaHelper.confirmarNota(nunotaAjusteSaida,null);

                    //Inserindo ajuste de estoque de entrada para as peças dos produtos do pedido com base no pedido origem
                    //DynamicVO modeloCABEntrada = buscaModelo(BigDecimal.valueOf(655343)); // ambiente de teste
                    DynamicVO modeloCABEntrada = buscaModelo(BigDecimal.valueOf(2514761)); // ambiente de producao
                    observacao = "Pedido de ajuste para disponibilizar as parte dos produtos que serão enviado para Nordeste. Pedido de origem " + nunota;
                    DynamicVO cabVOEntrada = inserirCabecalho(modeloCABEntrada, observacao, BigDecimal.valueOf(199));
                    nunotaAjusteEntrada = cabVOEntrada.asBigDecimal("NUNOTA");

                    qryPEDIDO.cleanParameters();
                    qryPEDIDO.setNamedParameter("NUNOTA", nunota);
                    rsPEDIDO = qryPEDIDO.executeQuery("SELECT CODMATPRIMA AS CODPROD, SUM(QTDNEG) AS QTDNEG, CODLOCALORIG, CODVOL FROM AD_PEDINTERCOMPANYPART WHERE NUNOTA = :NUNOTA GROUP BY CODMATPRIMA, CODLOCALORIG, CODVOL ORDER BY CODPROD");
                    //Inserindo os itens no pedido novo
                    while (rsPEDIDO.next()) {
                        inseriItem(cabVOEntrada, rsPEDIDO.getBigDecimal("CODPROD"), rsPEDIDO.getBigDecimal("QTDNEG"), rsPEDIDO.getBigDecimal("CODLOCALORIG"), rsPEDIDO.getString("CODVOL"));
                    }
                    rsPEDIDO.close();

                    //confirmacaoNota = new ConfirmacaoNotaHelper();
                    //ConfirmacaoNotaHelper.confirmarNota(nunotaAjusteEntrada,null);
                    int qtdeITENS_Incluidos;

                    //Inserindo pedido de venda das peças nacionais dos produtos do pedido com base no pedido origem
                    DynamicVO modeloCABNacional = buscaModelo(BigDecimal.valueOf(3361945));
                    observacao = "Pedido de ajuste para disponibilizar as parte dos produtos que serão enviado para Nordeste. Pedido de origem " + nunota;
                    DynamicVO cabVONacional = inserirCabecalho(modeloCABNacional, observacao, BigDecimal.valueOf(2251));
                    nunotaPedidoNacional = cabVONacional.asBigDecimal("NUNOTA");

                    qryPEDIDO.cleanParameters();
                    qryPEDIDO.setNamedParameter("NUNOTA", nunota);
                    rsPEDIDO = qryPEDIDO.executeQuery("SELECT CODMATPRIMA AS CODPROD, SUM(QTDNEG) AS QTDNEG, CODLOCALORIG, CODVOL FROM AD_PEDINTERCOMPANYPART WHERE NUNOTA = :NUNOTA AND ORIGEM = 'Nacional' GROUP BY CODMATPRIMA, CODLOCALORIG, CODVOL ORDER BY CODPROD");
                    //Inserindo os itens no pedido novo
                    qtdeITENS_Incluidos = 0;
                    while (rsPEDIDO.next()) {
                        inseriItem(cabVONacional, rsPEDIDO.getBigDecimal("CODPROD"), rsPEDIDO.getBigDecimal("QTDNEG"), rsPEDIDO.getBigDecimal("CODLOCALORIG"), rsPEDIDO.getString("CODVOL"));
                        qtdeITENS_Incluidos++;
                    }
                    rsPEDIDO.close();
                    try {
                        if(qtdeITENS_Incluidos > 0){
                            refazerFinanceiro(nunotaPedidoNacional);
                            recalcularImpost(nunotaPedidoNacional);
                            refazerFinanceiroForce(nunotaPedidoNacional);
                        }
                    } catch (Exception e) {
                         throw new Exception("Erro(nunotaPedidoNacional):  " + e.getMessage());
                    }

                    //Inserindo pedido de venda das peças importada dos produtos do pedido com base no pedido origem
                    DynamicVO modeloCABImportado = buscaModelo(BigDecimal.valueOf(498747));
                    observacao = "Pedido de ajuste para disponibilizar as parte dos produtos que serão enviado para Nordeste. Pedido de origem " + nunota;
                    DynamicVO cabVOImportado = inserirCabecalho(modeloCABImportado, observacao, BigDecimal.valueOf(2278));
                    nunotaPedidoImportado = cabVOImportado.asBigDecimal("NUNOTA");

                    qryPEDIDO.cleanParameters();
                    qryPEDIDO.setNamedParameter("NUNOTA", nunota);
                    rsPEDIDO = qryPEDIDO.executeQuery("SELECT CODMATPRIMA AS CODPROD, SUM(QTDNEG) AS QTDNEG, CODLOCALORIG, CODVOL FROM AD_PEDINTERCOMPANYPART WHERE NUNOTA = :NUNOTA AND ORIGEM = 'Importado' GROUP BY CODMATPRIMA, CODLOCALORIG, CODVOL ORDER BY CODPROD");
                    //Inserindo os itens no pedido novo
                    qtdeITENS_Incluidos = 0;
                    while (rsPEDIDO.next()) {
                        inseriItem(cabVOImportado, rsPEDIDO.getBigDecimal("CODPROD"), rsPEDIDO.getBigDecimal("QTDNEG"), rsPEDIDO.getBigDecimal("CODLOCALORIG"), rsPEDIDO.getString("CODVOL"));
                        qtdeITENS_Incluidos++;
                    }
                    rsPEDIDO.close();
                    try {
                        if(qtdeITENS_Incluidos > 0){
                            refazerFinanceiro(nunotaPedidoImportado);
                            recalcularImpost(nunotaPedidoImportado);
                            refazerFinanceiroForce(nunotaPedidoImportado);
                        }
                    } catch (Exception e) {
                         throw new Exception("Erro(nunotaPedidoImportado):  " + e.getMessage());
                    }

                    // atualizando informações do processo
                    NativeSql qryAlterada = new NativeSql(jdbc);
                    qryAlterada.setNamedParameter("nunotaAjusteSaida", nunotaAjusteSaida); //Ajuste de estoque para saida dos produto
                    qryAlterada.setNamedParameter("nunotaAjusteEntrada", nunotaAjusteEntrada); //Ajuste de estoque para entrada das pecas
                    qryAlterada.setNamedParameter("nunotaPedidoNacional", nunotaPedidoNacional); //Pedido de Destino com pecas nacional
                    qryAlterada.setNamedParameter("nunotaPedidoImportado", nunotaPedidoImportado); //Pedido de Destino com pecas importadas

                    qryAlterada.setNamedParameter("NUNOTA", nunota);
                    boolean rsOrig = qryAlterada.executeUpdate("UPDATE AD_PEDINTERCOMPANY SET NUNOTAAJUSTESAIDA = :nunotaAjusteSaida, NUNOTAAJUSTEENTRADA = :nunotaAjusteEntrada, NUNOTAPEDIDONACIONAL = :nunotaPedidoNacional, NUNOTAPEDIDOIMPORTADO = :nunotaPedidoImportado WHERE NUNOTA = :NUNOTA");
                    //Atualizacoes: Inserir log, refazer o financeiro no pedido novo, apresentar mensagem de sucesso.
                    contexto.setMensagemRetorno("Processo executado com sucesso, atualize a tela para verificar os lançamentos relacionados!");
                }

            }

        } finally {
            //Finalizando ação e fechando conexoes
            JapeSession.close(hnd);
            JdbcWrapper.closeSession(jdbc);
        }

    }

    private DynamicVO buscaModelo(BigDecimal nunota) throws Exception {
        DynamicVO modeloVO = (DynamicVO) dwFacade.findEntityByPrimaryKeyAsVO(DynamicEntityNames.CABECALHO_NOTA, nunota);
        return modeloVO;
    }

    private DynamicVO inserirCabecalho(DynamicVO origemVO, String observacao, BigDecimal newTop) throws Exception {

        DynamicVO novoPedidoVO = origemVO.buildClone();
        novoPedidoVO.setPrimaryKey(null);
        novoPedidoVO.clearReferences();

        String[] camposParaAnular = {"DHTIPOPER", "STATUSNOTA", "DTNEG", "HRMOV", "DTMOV", "VOLUME", "QTDVOL", "CHAVENFE",
            "CODMAQ", "DANFE", "DTENTSAI", "DTFATUR", "DHPROTOC", "DHREGDPEC", "NULOTENFE", "NULOTENFSE", "NUMALEATORIO", "NUMCOTACAO",
            "NUMNFSE", "NUMPROTOC", "NUMREGDPEC", "NUNOTA", "NUNOTAPEDFRET", "TPEMISNFE", "INDPRESNFCE", "TPEMISNFSE", "STATUSNFE", "STATUSNFSE",
            "COMGER", "NUREM"};

        for (String nomeCampo : camposParaAnular) {
            novoPedidoVO.setProperty(nomeCampo, null);
        }

        novoPedidoVO.setProperty("OBSERVACAO", observacao);
        novoPedidoVO.setProperty("CODTIPOPER", newTop);
        novoPedidoVO.setProperty("AD_ROTINAJUSTE", "S");
//        novoPedidoVO.setProperty("CODVEND", vendedor);
        novoPedidoVO.setProperty("AD_LIBERA_SEED", "N");
        novoPedidoVO.setProperty("AD_EXPEDIR", "S");
        novoPedidoVO.setProperty("AD_FATURAR", "N");
//        novoPedidoVO.setProperty("AD_OBSERVACAONFE", origemVO.asString("AD_OBSERVACAONFE"));

        PersistentLocalEntity cabEntity = null;
        try {
            cabEntity = dwFacade.createEntity(DynamicEntityNames.CABECALHO_NOTA, (EntityVO) novoPedidoVO);
        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception(e.getMessage());
        }

        return (DynamicVO) cabEntity.getValueObject();

    }

    private void inseriItem(DynamicVO cabecalhoVO, BigDecimal codprod, BigDecimal qtdNeg, BigDecimal codlocal, String codvol) throws Exception {

        CentralItemNota centralItemNota = new CentralItemNota();
        ParamsInicializacaoProduto params = new CentralItemNota.ParamsInicializacaoProduto();

        BigDecimal codtop = cabecalhoVO.asBigDecimalOrZero("CODTIPOPER");

        DynamicVO topVO = ComercialUtils.getTipoOperacao(codtop);
        String topAtualEst = topVO.asString("ATUALEST");
        BigDecimal topAtualEstMP = topVO.asBigDecimal("ATUALESTMP");
        String estNaConfirmacao = topVO.asString("ADIARATUALEST");

        params.nuNota = cabecalhoVO.asBigDecimal("NUNOTA");
        params.chamadoPelaTela = true;
        params.codProd = codprod;

        PrecoUnitarioInfo pui = centralItemNota.inicializaProduto(params);

        DynamicVO itemVO = (DynamicVO) dwFacade.getDefaultValueObjectInstance(DynamicEntityNames.ITEM_NOTA);

        itemVO.setProperty("NUNOTA", cabecalhoVO.asBigDecimal("NUNOTA"));
        itemVO.setProperty("CODPROD", codprod);
        itemVO.setProperty("QTDNEG", BigDecimalUtil.getRounded(qtdNeg, BigDecimalUtil.getValueOrZero(pui.getDecQtd()).intValue()));
        itemVO.setProperty("CODVOL", codvol);
        itemVO.setProperty("VLRUNIT", pui.getVlrUnit());
        itemVO.setProperty("CUSTO", pui.getCusto());
        itemVO.setProperty("NUTAB", pui.getNuTab());
        itemVO.setProperty("PRECOBASE", pui.getPrecoBase());
        itemVO.setProperty("VLRCUS", pui.getVlrCus());
        itemVO.setProperty("PERCDESC", pui.getPercDesc());
        itemVO.setProperty("VLRDESC", BigDecimalUtil.getRounded(BigDecimalUtil.getRounded(pui.getVlrUnit().multiply(BigDecimalUtil.getRounded(qtdNeg, BigDecimalUtil.getValueOrZero(pui.getDecQtd()).intValue())), 2).multiply((pui.getPercDesc()).divide(BigDecimal.valueOf(100))), 2));
        itemVO.setProperty("USOPROD", pui.getUsoProd());
        itemVO.setProperty("VLRTOT", BigDecimalUtil.getRounded(pui.getVlrUnit().multiply(BigDecimalUtil.getRounded(qtdNeg, BigDecimalUtil.getValueOrZero(pui.getDecQtd()).intValue())), 2));
        itemVO.setProperty("CODLOCALORIG", codlocal);

//        if (pui.getUsoProd().equals("M")) {
//            itemVO.setProperty("ATUALESTOQUE", topAtualEstMP);
//        } else {
        if ("B".equals(topAtualEst)) {
            itemVO.setProperty("ATUALESTOQUE", valueOf(-1));
        } else {
            itemVO.setProperty("ATUALESTOQUE", ("R-E".indexOf(topAtualEst) > -1) ? ONE : ZERO);
        }

        if ("S".equals(estNaConfirmacao)) {
            itemVO.setProperty("ATUALESTOQUE", ZERO);
        }
//        }

        if ("R".equals(topAtualEst)) {
            itemVO.setProperty("RESERVA", "S");
        } else {
            itemVO.setProperty("RESERVA", "N");
        }

        dwFacade.createEntity(DynamicEntityNames.ITEM_NOTA, (EntityVO) itemVO);

        centralItemNota.recalcularValores("QTDNEG", null, itemVO, cabecalhoVO.asBigDecimal("NUNOTA"));

        recalcularImpost(cabecalhoVO.asBigDecimal("NUNOTA"));
    }

    private void recalcularImpost(BigDecimal nunota) throws Exception {

        EntityFacade dwfFacade = EntityFacadeFactory.getDWFFacade();

        DynamicVO cabVO = (DynamicVO) dwfFacade.findEntityByPrimaryKeyAsVO("CabecalhoNota", new Object[]{nunota});
        ImpostosHelpper impostosHelper = new ImpostosHelpper();
        impostosHelper.setForcarRecalculo(true);
        impostosHelper.carregarNota(cabVO);
        impostosHelper.totalizarNota(nunota);
        impostosHelper.setCalcularTudo(true);
        impostosHelper.calculaICMS(true);

    }

    public static void refazerFinanceiro(BigDecimal nunota) throws Exception {

        EntityFacade dwfFacade = EntityFacadeFactory.getDWFFacade();
        DynamicVO cabVO = (DynamicVO) dwfFacade.findEntityByPrimaryKeyAsVO("CabecalhoNota", new Object[]{nunota});

        CentralFinanceiro financeiro = new CentralFinanceiro();

        financeiro.inicializaNota(nunota);
        financeiro.refazerFinanceiro();

    }

    public void refazerFinanceiroForce(BigDecimal nunota) throws Exception {
        try {

            JapeSessionContext.putProperty("br.com.sankhya.com.CentralCompraVenda", Boolean.TRUE);
            JapeSessionContext.putProperty("ItemNota.incluindo.alterando.pela.central", Boolean.TRUE);
            JapeSessionContext.putProperty("calcular.outros.impostos", "true");

            CentralFinanceiro financeiro = new CentralFinanceiro();
            financeiro.inicializaNota(nunota);
            financeiro.refazerFinanceiro();
        } finally {
            JapeSessionContext.putProperty("br.com.sankhya.com.CentralCompraVenda", Boolean.FALSE);
            JapeSessionContext.putProperty("ItemNota.incluindo.alterando.pela.central", Boolean.FALSE);
            JapeSessionContext.putProperty("calcular.outros.impostos", "false");
        }
    }

    public void excluirPedido(BigDecimal nunota, JdbcWrapper jdbc) throws Exception {
        try {
            jdbc.openSession();      
            UsuarioVO usuarioVO = (UsuarioVO) JapeSessionContext.getProperties().get("usuarioVO"); 
            
            NativeSql qryParams = new NativeSql(jdbc);
            qryParams.cleanParameters();
            qryParams.setNamedParameter("IDSESSAO", usuarioVO.getINTERNO());
            qryParams.executeUpdate("DELETE EXECPARAMS WHERE IDSESSAO = :IDSESSAO");
                    
            qryParams.cleanParameters();
            qryParams.setNamedParameter("IDSESSAO", usuarioVO.getINTERNO());
            qryParams.setNamedParameter("SEQUENCIA", "0");
            qryParams.setNamedParameter("NOME", "JUSTIFICATIVA");
            qryParams.setNamedParameter("TIPO", "S");
            qryParams.setNamedParameter("TEXTO", "Pedido de produto intercompany transformado em pedido de peças");
            qryParams.executeUpdate("INSERT INTO EXECPARAMS(IDSESSAO, SEQUENCIA, NOME, TIPO, NUMINT, NUMDEC, TEXTO, DTA) VALUES(:IDSESSAO, :SEQUENCIA, :NOME, :TIPO, null, null, :TEXTO, null)");

            qryParams.cleanParameters();
            qryParams.setNamedParameter("IDSESSAO", usuarioVO.getINTERNO());
            qryParams.setNamedParameter("SEQUENCIA", "1");
            qryParams.setNamedParameter("NOME", "__CONFIRMACAO__");
            qryParams.setNamedParameter("TIPO", "S");
            qryParams.setNamedParameter("TEXTO", "S");
            qryParams.executeUpdate("INSERT INTO EXECPARAMS(IDSESSAO, SEQUENCIA, NOME, TIPO, NUMINT, NUMDEC, TEXTO, DTA) VALUES(:IDSESSAO, :SEQUENCIA, :NOME, :TIPO, null, null, :TEXTO, null)");

            qryParams.cleanParameters();
            qryParams.setNamedParameter("IDSESSAO", usuarioVO.getINTERNO());
            qryParams.setNamedParameter("SEQUENCIA", "1");
            qryParams.setNamedParameter("NOME", "NUNOTA");
            qryParams.setNamedParameter("TIPO", "I");
            qryParams.setNamedParameter("NUMINT", nunota);
            qryParams.executeUpdate("INSERT INTO EXECPARAMS(IDSESSAO, SEQUENCIA, NOME, TIPO, NUMINT, NUMDEC, TEXTO, DTA) VALUES(:IDSESSAO, :SEQUENCIA, :NOME, :TIPO, :NUMINT, null, null, null)");
                    
            CallableStatement rsProc = jdbc.getConnection().prepareCall("{call AD_STP_DLTPED(STP_GET_CODUSULOGADO, :P_IDSESSAO, :P_QTDLINHAS, :P_MENSAGEM)}");
            rsProc.setString("P_IDSESSAO", usuarioVO.getINTERNO());
            rsProc.setString("P_QTDLINHAS", "1");
            rsProc.setString("P_MENSAGEM", " ");
            rsProc.execute();
            rsProc.close();

            qryParams.cleanParameters();
            qryParams.setNamedParameter("IDSESSAO", usuarioVO.getINTERNO());
            qryParams.executeUpdate("DELETE EXECPARAMS WHERE IDSESSAO = :IDSESSAO");
                    
            jdbc.closeSession();
        } catch (Exception e) {
            throw new Exception("Erro:  " + e.getMessage());
        }     
    }
}
