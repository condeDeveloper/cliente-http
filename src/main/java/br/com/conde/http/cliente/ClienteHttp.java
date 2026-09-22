package br.com.conde.http.cliente;

import br.com.conde.http.mensagem.Cabecalhos;
import br.com.conde.http.mensagem.ErroDeHttp;
import br.com.conde.http.mensagem.LeitorDeResposta;
import br.com.conde.http.mensagem.Resposta;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Um cliente HTTP/1.1 sobre {@link Socket}.
 *
 * <p>HTTP é texto sobre TCP, e uma requisição inteira cabe em cinco linhas:
 *
 * <pre>
 *   GET /caminho HTTP/1.1
 *   Host: exemplo.com
 *   Connection: close
 *   (linha em branco)
 * </pre>
 *
 * <p>O {@code Host} é obrigatório no HTTP/1.1 e foi a mudança que permitiu
 * hospedar mil sites num IP só: sem ele, o servidor não tem como saber qual
 * dos sites você quer, porque o TCP só carrega o endereço. É por isso que
 * omiti-lo devolve 400 em praticamente todo servidor.
 */
public final class ClienteHttp {

  private final int prazoDeConexao;
  private final int prazoDeLeitura;
  private final int maximoDeRedirecionamentos;
  private final boolean aceitarCompressao;
  private final int corpoMaximo;

  private ClienteHttp(Construtor construtor) {
    this.prazoDeConexao = construtor.prazoDeConexao;
    this.prazoDeLeitura = construtor.prazoDeLeitura;
    this.maximoDeRedirecionamentos = construtor.maximoDeRedirecionamentos;
    this.aceitarCompressao = construtor.aceitarCompressao;
    this.corpoMaximo = construtor.corpoMaximo;
  }

  public static Construtor construtor() {
    return new Construtor();
  }

  /** Um cliente com os padrões. */
  public static ClienteHttp padrao() {
    return construtor().montar();
  }

  /** Configuração do cliente. */
  public static final class Construtor {

    private int prazoDeConexao = 10_000;
    private int prazoDeLeitura = 30_000;
    private int maximoDeRedirecionamentos = 5;
    private boolean aceitarCompressao = true;
    private int corpoMaximo = LeitorDeResposta.MAXIMO_PADRAO;

    public Construtor prazoDeConexao(int milissegundos) {
      this.prazoDeConexao = milissegundos;

      return this;
    }

    public Construtor prazoDeLeitura(int milissegundos) {
      this.prazoDeLeitura = milissegundos;

      return this;
    }

    public Construtor maximoDeRedirecionamentos(int quantos) {
      this.maximoDeRedirecionamentos = quantos;

      return this;
    }

    public Construtor aceitarCompressao(boolean sim) {
      this.aceitarCompressao = sim;

      return this;
    }

    public Construtor corpoMaximo(int bytes) {
      this.corpoMaximo = bytes;

      return this;
    }

    public ClienteHttp montar() {
      return new ClienteHttp(this);
    }
  }

  public Resposta obter(String url) throws IOException {
    return enviar(Requisicao.obter(url));
  }

  public Resposta postar(String url, String corpo, String tipo) throws IOException {
    return enviar(Requisicao.postar(url, corpo, tipo));
  }

  /**
   * Envia a requisição, seguindo redirecionamento.
   *
   * <p>O laço tem teto porque um servidor mal configurado pode apontar para si
   * mesmo — e um cliente sem teto fica ali para sempre.
   */
  public Resposta enviar(Requisicao requisicao) throws IOException {
    List<String> caminho = new ArrayList<>();
    Requisicao atual = requisicao;

    for (int volta = 0; ; volta += 1) {
      Resposta resposta = enviarUmaVez(atual);

      if (!resposta.redirecionamento() || resposta.cabecalho("Location").isEmpty()) {
        return new Resposta(
            resposta.status(), resposta.motivo(), resposta.cabecalhos(), resposta.corpo(), List.copyOf(caminho));
      }

      if (volta >= maximoDeRedirecionamentos) {
        throw new ErroDeHttp("Mais de " + maximoDeRedirecionamentos + " redirecionamentos a partir de " + requisicao.url());
      }

      // O Location pode ser relativo — `/entrar` em vez da URL inteira —, e
      // `resolve` da URI faz a junção certa, inclusive com `..`.
      URI destino = URI.create(atual.url()).resolve(resposta.cabecalho("Location").orElseThrow().trim());

      caminho.add(destino.toString());

      // 303 sempre vira GET; 301 e 302 viram GET na prática, porque foi assim
      // que os navegadores fizeram e o mundo se acostumou. Só 307 e 308
      // preservam o método, que foi exatamente para isso que eles existem.
      boolean preservaMetodo = resposta.status() == 307 || resposta.status() == 308;

      atual =
          preservaMetodo
              ? atual.paraUrl(destino.toString())
              : Requisicao.obter(destino.toString()).comCabecalhos(atual.cabecalhos());
    }
  }

  private Resposta enviarUmaVez(Requisicao requisicao) throws IOException {
    URI uri = URI.create(requisicao.url());
    String esquema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);

    if (!esquema.equals("http") && !esquema.equals("https")) {
      throw new ErroDeHttp("Só http e https: " + requisicao.url());
    }

    boolean seguro = esquema.equals("https");
    int porta = uri.getPort() > 0 ? uri.getPort() : (seguro ? 443 : 80);

    if (uri.getHost() == null) {
      throw new ErroDeHttp("URL sem máquina: " + requisicao.url());
    }

    try (Socket soquete = abrir(uri.getHost(), porta, seguro)) {
      soquete.setSoTimeout(prazoDeLeitura);

      OutputStream saida = soquete.getOutputStream();

      saida.write(montarRequisicao(requisicao, uri).getBytes(StandardCharsets.ISO_8859_1));

      if (requisicao.corpo().length > 0) {
        saida.write(requisicao.corpo());
      }

      saida.flush();

      InputStream entrada = soquete.getInputStream();

      return new LeitorDeResposta(entrada, corpoMaximo).ler(requisicao.metodo().equals("HEAD"));
    }
  }

  private Socket abrir(String maquina, int porta, boolean seguro) throws IOException {
    if (!seguro) {
      Socket soquete = new Socket();

      soquete.connect(new InetSocketAddress(maquina, porta), prazoDeConexao);

      return soquete;
    }

    // Para TLS o cliente não implementa nada: entrega ao `SSLSocketFactory`,
    // que já traz os certificados confiáveis do JDK. Escrever TLS na mão seria
    // outro projeto — e não é um projeto que valha escrever para usar.
    Socket base = new Socket();

    base.connect(new InetSocketAddress(maquina, porta), prazoDeConexao);

    SSLSocket comTls =
        (SSLSocket)
            ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(base, maquina, porta, true);

    // Isto **não** vem ligado por padrão num SSLSocket, e é a pegadinha de
    // segurança mais cara do java.net: sem ele o JDK confere se o certificado
    // é confiável, mas não se ele foi emitido para esta máquina — e um
    // certificado válido de outro domínio passa.
    javax.net.ssl.SSLParameters parametros = comTls.getSSLParameters();

    parametros.setEndpointIdentificationAlgorithm("HTTPS");
    comTls.setSSLParameters(parametros);

    return comTls;
  }

  /** Monta o texto da requisição, até a linha em branco. */
  String montarRequisicao(Requisicao requisicao, URI uri) {
    String caminho = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();

    if (uri.getRawQuery() != null) {
      caminho += "?" + uri.getRawQuery();
    }

    Cabecalhos cabecalhos = new Cabecalhos();

    // O Host tem que vir com a porta quando ela não é a padrão do esquema.
    boolean portaPadrao = uri.getPort() < 0 || uri.getPort() == (uri.getScheme().equals("https") ? 443 : 80);

    cabecalhos.definir("Host", portaPadrao ? uri.getHost() : uri.getHost() + ":" + uri.getPort());
    cabecalhos.definir("User-Agent", "cliente-http/1.0");

    if (aceitarCompressao) {
      cabecalhos.definir("Accept-Encoding", "gzip, deflate");
    }

    for (String nome : requisicao.cabecalhos().nomes()) {
      for (String valor : requisicao.cabecalhos().todos(nome)) {
        cabecalhos.definir(nome, valor);
      }
    }

    // Sem tamanho declarado o servidor não sabe onde o corpo acaba e fica
    // esperando; um POST sem Content-Length trava até o prazo estourar.
    if (requisicao.corpo().length > 0) {
      cabecalhos.definir("Content-Length", String.valueOf(requisicao.corpo().length));
    }

    // Uma conexão por requisição, e por isso `close`: manter viva exigiria um
    // pool, que é o limite declarado deste projeto.
    cabecalhos.definir("Connection", "close");

    return requisicao.metodo() + " " + caminho + " HTTP/1.1\r\n" + cabecalhos.comoTexto() + "\r\n";
  }
}
