package br.com.conde.http.mensagem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Lê uma resposta do fluxo do soquete.
 *
 * <p><b>A armadilha número um do HTTP escrito à mão</b> está na primeira
 * linha deste arquivo: não dá para envolver o {@code InputStream} do soquete
 * num {@code BufferedReader} para ler os cabeçalhos. O leitor decodifica e
 * <b>lê adiante</b>, engolindo bytes do corpo que nunca mais voltam. O corpo
 * então chega curto, ou vazio, ou embaralhado — e o sintoma não tem relação
 * nenhuma com a causa.
 *
 * <p>Aqui os cabeçalhos são lidos <b>byte a byte</b> do mesmo fluxo que o
 * corpo vai usar, procurando o CRLF na mão. Feio? É. Correto? Também.
 */
public final class LeitorDeResposta {

  /** Teto de tamanho, para uma resposta hostil não consumir toda a memória. */
  public static final int MAXIMO_PADRAO = 32 * 1024 * 1024;

  private final InputStream entrada;
  private final int maximo;

  public LeitorDeResposta(InputStream entrada) {
    this(entrada, MAXIMO_PADRAO);
  }

  public LeitorDeResposta(InputStream entrada, int maximo) {
    this.entrada = entrada;
    this.maximo = maximo;
  }

  /** Lê a resposta inteira. */
  public Resposta ler(boolean semCorpo) throws IOException {
    String linhaInicial = lerLinha();

    if (linhaInicial.isEmpty()) {
      throw new ErroDeHttp("O servidor fechou sem responder nada");
    }

    String[] partes = linhaInicial.split(" ", 3);

    if (partes.length < 2 || !partes[0].startsWith("HTTP/")) {
      throw new ErroDeHttp("Linha de status inesperada: " + linhaInicial);
    }

    int status;

    try {
      status = Integer.parseInt(partes[1]);
    } catch (NumberFormatException erro) {
      throw new ErroDeHttp("Status não numérico: " + partes[1], erro);
    }

    Cabecalhos cabecalhos = lerCabecalhos();
    byte[] corpo = semCorpo || semCorpoPorStatus(status) ? new byte[0] : lerCorpo(cabecalhos);

    return new Resposta(status, partes.length > 2 ? partes[2] : "", cabecalhos, descomprimir(cabecalhos, corpo), List.of());
  }

  /**
   * Respostas que nunca têm corpo, mesmo declarando tamanho.
   *
   * <p>Esperar corpo num 204 trava a leitura até o prazo estourar: o servidor
   * não vai mandar nada e não vai fechar.
   */
  public static boolean semCorpoPorStatus(int status) {
    return status == 204 || status == 304 || (status >= 100 && status < 200);
  }

  /** Lê uma linha terminada em CRLF, sem decodificar nada antes da hora. */
  private String lerLinha() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    for (; ; ) {
      int b = entrada.read();

      if (b < 0) {
        break;
      }

      if (b == '\n') {
        break;
      }

      if (b != '\r') {
        bytes.write(b);
      }

      if (bytes.size() > 8192) {
        throw new ErroDeHttp("Linha de cabeçalho passou de 8 KB");
      }
    }

    // Os cabeçalhos são ASCII por definição; qualquer outra coisa é o
    // servidor fugindo do padrão.
    return bytes.toString(StandardCharsets.ISO_8859_1);
  }

  private Cabecalhos lerCabecalhos() throws IOException {
    Cabecalhos cabecalhos = new Cabecalhos();

    for (; ; ) {
      String linha = lerLinha();

      // A linha em branco é o fim dos cabeçalhos e o começo do corpo.
      if (linha.isEmpty()) {
        return cabecalhos;
      }

      String[] par = Cabecalhos.partir(linha);

      cabecalhos.acrescentar(par[0], par[1]);

      if (cabecalhos.quantidade() > 200) {
        throw new ErroDeHttp("Resposta com mais de 200 cabeçalhos");
      }
    }
  }

  private byte[] lerCorpo(Cabecalhos cabecalhos) throws IOException {
    // A ordem importa: quando os dois vêm, o `chunked` manda. É o que a RFC
    // diz, e ignorar isso é uma via clássica de contrabando de requisição.
    if (cabecalhos.valeComo("Transfer-Encoding", "chunked")) {
      return lerEmPedacos();
    }

    var tamanho = cabecalhos.primeiro("Content-Length");

    if (tamanho.isPresent()) {
      int quantos;

      try {
        quantos = Integer.parseInt(tamanho.get().trim());
      } catch (NumberFormatException erro) {
        throw new ErroDeHttp("Content-Length não numérico: " + tamanho.get(), erro);
      }

      if (quantos < 0 || quantos > maximo) {
        throw new ErroDeHttp("Content-Length de " + quantos + " bytes fora do limite de " + maximo);
      }

      return lerExatamente(quantos);
    }

    // Sem tamanho e sem chunked, o fim do corpo é o fim da conexão — que é o
    // jeito do HTTP/1.0 e o motivo de ele não ter keep-alive de verdade.
    return lerAteFechar();
  }

  /**
   * Lê o corpo em pedaços.
   *
   * <p>O formato é {@code <tamanho em hexadecimal> CRLF <dados> CRLF},
   * terminado por um pedaço de tamanho zero. Ele existe porque o servidor nem
   * sempre sabe o tamanho antes de começar a responder — é o que permite
   * transmitir algo gerado na hora sem segurar tudo em memória primeiro.
   *
   * <p>O tamanho pode vir com extensões depois de um ponto e vírgula
   * ({@code 1a;nome=valor}), e quem não corta nelas lê um hexadecimal
   * inválido.
   */
  private byte[] lerEmPedacos() throws IOException {
    ByteArrayOutputStream corpo = new ByteArrayOutputStream();

    for (; ; ) {
      String linha = lerLinha().trim();

      if (linha.isEmpty()) {
        continue;
      }

      int pontoEVirgula = linha.indexOf(';');
      String hexadecimal = pontoEVirgula < 0 ? linha : linha.substring(0, pontoEVirgula);
      int tamanho;

      try {
        tamanho = Integer.parseInt(hexadecimal.trim(), 16);
      } catch (NumberFormatException erro) {
        throw new ErroDeHttp("Tamanho de pedaço inválido: " + linha, erro);
      }

      if (tamanho == 0) {
        // Depois do pedaço zero vêm os "trailers", terminados por linha vazia.
        while (!lerLinha().isEmpty()) {
          // Trailers não são usados aqui, mas precisam ser consumidos para a
          // conexão poder ser reaproveitada.
        }

        return corpo.toByteArray();
      }

      if (corpo.size() + tamanho > maximo) {
        throw new ErroDeHttp("O corpo passou do limite de " + maximo + " bytes");
      }

      corpo.write(lerExatamente(tamanho));

      // O CRLF que fecha o pedaço.
      lerLinha();
    }
  }

  private byte[] lerExatamente(int quantos) throws IOException {
    byte[] bytes = new byte[quantos];
    int lidos = 0;

    while (lidos < quantos) {
      // `read` pode devolver menos do que foi pedido, e quase sempre devolve:
      // um laço é obrigatório, não uma precaução.
      int agora = entrada.read(bytes, lidos, quantos - lidos);

      if (agora < 0) {
        throw new ErroDeHttp("A conexão fechou com " + lidos + " de " + quantos + " bytes do corpo");
      }

      lidos += agora;
    }

    return bytes;
  }

  private byte[] lerAteFechar() throws IOException {
    ByteArrayOutputStream corpo = new ByteArrayOutputStream();
    byte[] pedaco = new byte[8192];

    for (; ; ) {
      int lidos = entrada.read(pedaco);

      if (lidos < 0) {
        return corpo.toByteArray();
      }

      if (corpo.size() + lidos > maximo) {
        throw new ErroDeHttp("O corpo passou do limite de " + maximo + " bytes");
      }

      corpo.write(pedaco, 0, lidos);
    }
  }

  /** Desfaz o gzip ou o deflate, quando o servidor comprimiu. */
  private byte[] descomprimir(Cabecalhos cabecalhos, byte[] corpo) throws IOException {
    if (corpo.length == 0) {
      return corpo;
    }

    String codificacao = cabecalhos.primeiro("Content-Encoding").orElse("").trim().toLowerCase(java.util.Locale.ROOT);

    if (codificacao.isEmpty() || codificacao.equals("identity")) {
      return corpo;
    }

    var bytes = new java.io.ByteArrayInputStream(corpo);

    try (InputStream fluxo =
        switch (codificacao) {
          case "gzip", "x-gzip" -> new GZIPInputStream(bytes);
          case "deflate" -> new InflaterInputStream(bytes);
          default -> throw new ErroDeHttp("Content-Encoding desconhecido: " + codificacao);
        }) {
      return fluxo.readAllBytes();
    }
  }
}
