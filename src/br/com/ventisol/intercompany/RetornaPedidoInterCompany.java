/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
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
import br.com.sankhya.jape.wrapper.JapeFactory;
import br.com.sankhya.jape.wrapper.JapeWrapper;
import br.com.sankhya.modelcore.comercial.CentralFinanceiro;
import br.com.sankhya.modelcore.comercial.CentralItemNota;
import br.com.sankhya.modelcore.comercial.CentralItemNota.ParamsInicializacaoProduto;
import br.com.sankhya.modelcore.comercial.ComercialUtils;
import br.com.sankhya.modelcore.comercial.ComercialUtils.PrecoUnitarioInfo;
import br.com.sankhya.modelcore.comercial.impostos.ImpostosHelpper;
import br.com.sankhya.modelcore.dwfdata.vo.tsi.UsuarioVO;
import br.com.sankhya.modelcore.util.DynamicEntityNames;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;
import com.sankhya.util.BigDecimalUtil;
import static java.lang.String.*;
import static java.math.BigDecimal.*;

/**
 *
 * @author projeto5
 */
public class RetornaPedidoInterCompany implements AcaoRotinaJava {

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

                //Validacao sobre geracao do pedido
                if (contexto.confirmarSimNao("Desfazer pedido INTERCOMPANY", "Desfazer os  lancamentos do Pedido de Nro. " + nunota, 1)) {

                    //verificando se o pedido já foi desmembrado
                    qryPEDIDO.setNamedParameter("NUNOTA", nunota);
                    ResultSet rsPEDIDO = qryPEDIDO.executeQuery("SELECT NUNOTAAJUSTESAIDA, NUNOTAAJUSTEENTRADA, NUNOTAPEDIDONACIONAL, NUNOTAPEDIDOIMPORTADO FROM AD_PEDINTERCOMPANY WHERE NUNOTA = :NUNOTA AND NUNOTAAJUSTESAIDA IS NOT NULL AND NUNOTAAJUSTEENTRADA IS NOT NULL AND (NUNOTAPEDIDONACIONAL IS NOT NULL OR NUNOTAPEDIDOIMPORTADO IS NOT NULL)");

                    qtdeRS = 0;
                    while (rsPEDIDO.next()) {
                        nunotaAjusteSaida = rsPEDIDO.getBigDecimal("NUNOTAAJUSTESAIDA"); //Ajuste de estoque para saida dos produto
                        nunotaAjusteEntrada = rsPEDIDO.getBigDecimal("NUNOTAAJUSTEENTRADA"); //Ajuste de estoque para entrada das pecas
                        nunotaPedidoNacional = rsPEDIDO.getBigDecimal("NUNOTAPEDIDONACIONAL"); //Pedido de Destino com pecas nacional
                        nunotaPedidoImportado = rsPEDIDO.getBigDecimal("NUNOTAPEDIDOIMPORTADO"); //Pedido de Destino com pecas importadas
                        qtdeRS++;
                    }

                    if (qtdeRS <= 0) {
                        throw new Exception("O registro " + nunota + " não pode ser processado, pois não tem pedidos de ajuste, pedido de venda e pedido de revenda associado");
                    }

                    rsPEDIDO.close();

                    //VERIFICANDO SE ALGUM PEDIDO (NACIONAL/IMPORTADO) JÁ FOI FATURADO
                    //REMOVENDO AJUSTE DE SAIDA                
                    NativeSql qryAlterada = new NativeSql(jdbc);
                    qryAlterada.setNamedParameter("NUNOTA", nunota);
                    boolean rsOrig = qryAlterada.executeUpdate("UPDATE AD_PEDINTERCOMPANY SET NUNOTAAJUSTESAIDA = null WHERE NUNOTA = :NUNOTA");

                    try {
                        JapeWrapper ajusteCABSaidaDAO = JapeFactory.dao(DynamicEntityNames.CABECALHO_NOTA);
                        ajusteCABSaidaDAO.delete(nunotaAjusteSaida);
                    } catch (Exception e) {

                        qryAlterada = new NativeSql(jdbc);
                        qryAlterada.setNamedParameter("NUNOTA", nunota);
                        qryAlterada.setNamedParameter("NUNOTAAJUSTESAIDA", nunotaAjusteSaida);
                        rsOrig = qryAlterada.executeUpdate("UPDATE AD_PEDINTERCOMPANY SET NUNOTAAJUSTESAIDA = :NUNOTAAJUSTESAIDA WHERE NUNOTA = :NUNOTA");

                        throw new Exception("Erro ao remover o pedido de ajuste de estoque " + nunotaAjusteSaida + ". Erro: " + e.getMessage());
                    }

                    //REMOVENDO AJUSTE DE ENTRADA
                    qryAlterada = new NativeSql(jdbc);
                    qryAlterada.setNamedParameter("NUNOTA", nunota);
                    rsOrig = qryAlterada.executeUpdate("UPDATE AD_PEDINTERCOMPANY SET NUNOTAAJUSTEENTRADA = null WHERE NUNOTA = :NUNOTA");

                    try {
                        JapeWrapper ajusteCABSaidaDAO = JapeFactory.dao(DynamicEntityNames.CABECALHO_NOTA);
                        ajusteCABSaidaDAO.delete(nunotaAjusteEntrada);
                    } catch (Exception e) {

                        qryAlterada = new NativeSql(jdbc);
                        qryAlterada.setNamedParameter("NUNOTA", nunota);
                        qryAlterada.setNamedParameter("NUNOTAAJUSTEENTRADA", nunotaAjusteEntrada);
                        rsOrig = qryAlterada.executeUpdate("UPDATE AD_PEDINTERCOMPANY SET NUNOTAAJUSTEENTRADA = :NUNOTAAJUSTEENTRADA WHERE NUNOTA = :NUNOTA");

                        throw new Exception("Erro ao remover o pedido de ajuste de estoque " + nunotaAjusteEntrada + ". Erro: " + e.getMessage());
                    }

                    //REMOVENDO PEDIDO NACIONAL
                    qryAlterada = new NativeSql(jdbc);
                    qryAlterada.setNamedParameter("NUNOTA", nunota);
                    rsOrig = qryAlterada.executeUpdate("UPDATE AD_PEDINTERCOMPANY SET NUNOTAPEDIDONACIONAL = null WHERE NUNOTA = :NUNOTA");
                    try {

                        excluirPedido(nunotaPedidoNacional, jdbc);

                    } catch (Exception e) {
                        qryAlterada = new NativeSql(jdbc);
                        qryAlterada.setNamedParameter("NUNOTA", nunota);
                        qryAlterada.setNamedParameter("NUNOTAPEDIDONACIONAL", nunotaPedidoNacional);
                        rsOrig = qryAlterada.executeUpdate("UPDATE AD_PEDINTERCOMPANY SET NUNOTAPEDIDONACIONAL = :NUNOTAPEDIDONACIONAL WHERE NUNOTA = :NUNOTA");

                        throw new Exception("Erro ao remover o pedido de venda de estoque " + nunotaPedidoNacional + ". Erro: :  " + e.getMessage());
                    }

                    //REMOVENDO PEDIDO IMPORTADO
                    qryAlterada = new NativeSql(jdbc);
                    qryAlterada.setNamedParameter("NUNOTA", nunota);
                    rsOrig = qryAlterada.executeUpdate("UPDATE AD_PEDINTERCOMPANY SET NUNOTAPEDIDOIMPORTADO = null WHERE NUNOTA = :NUNOTA");
                    try {

                        excluirPedido(nunotaPedidoImportado, jdbc);

                    } catch (Exception e) {
                        qryAlterada = new NativeSql(jdbc);
                        qryAlterada.setNamedParameter("NUNOTA", nunota);
                        qryAlterada.setNamedParameter("NUNOTAPEDIDOIMPORTADO", nunotaPedidoImportado);
                        rsOrig = qryAlterada.executeUpdate("UPDATE AD_PEDINTERCOMPANY SET NUNOTAPEDIDOIMPORTADO = :NUNOTAPEDIDOIMPORTADO WHERE NUNOTA = :NUNOTA");

                        throw new Exception("Erro ao remover o pedido de revenda de estoque " + nunotaPedidoImportado + ". Erro: :  " + e.getMessage());
                    }

                    //RETONANDO PEDIDO ORIGINAL
                    try {
                        jdbc.openSession();
                        CallableStatement rsProc = jdbc.getConnection().prepareCall("{CALL AD_STP_PEDINTERCOMPANY_CANC(?)}");
                        rsProc.setBigDecimal(1, nunota);
                        rsProc.execute();
                        rsProc.close();
                        jdbc.closeSession();
                    } catch (Exception e) {
                        throw new Exception("Erro ao retornar o pedido " + nunota + ". Erro:  " + e.getMessage());
                    }

                    contexto.setMensagemRetorno("Processo executado com sucesso, atualize a tela para verificar os lançamentos relacionados e volte ao portal para verificar o pedido!");
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
            qryParams.setNamedParameter("TEXTO", "Pedido de pecas intercompany retornado em pedido de produtos");
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
