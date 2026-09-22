package br.com.conde.http.mensagem;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Uma resposta HTTP já lida por inteiro.
 *
 * <p>O corpo fica como bytes e só vira texto quando alguém pede — usando o
 * {@code charset} que o servidor declarou no {@code Content-Type}. Decodificar
 * cedo, em UTF-8 por reflexo, é como se perde acento em resposta latin-1.
 */
public record Resposta(int status, String motivo, Cabecalhos cabecalhos, byte[] corpo, List<String> redirecionamentos) {

  /** A resposta deu certo? */
  public boolean ok() {
    return status >= 200 && status < 300;
  }

  public boolean redirecionamento() {
    return status >= 300 && status < 400;
  }

  /** O corpo como texto, no charset que o servidor declarou. */
  public String texto() {
    return new String(corpo, charset());
  }

  /**
   * O charset do Content-Type, ou ISO-8859-1.
   *
   * <p>O padrão do HTTP/1.1 é latin-1, não UTF-8 — e isso surpreende todo
   * mundo. Na prática quase todo servidor declara, mas quem não declara
   * espera latin-1.
   */
  public Charset charset() {
    String tipo = cabecalhos.primeiro("Content-Type").orElse("");

    for (String parte : tipo.split(";")) {
      String limpo = parte.trim();

      if (limpo.toLowerCase(java.util.Locale.ROOT).startsWith("charset=")) {
        try {
          return Charset.forName(limpo.substring(8).trim().replace("\"", ""));
        } catch (RuntimeException ignorado) {
          return StandardCharsets.ISO_8859_1;
        }
      }
    }

    return StandardCharsets.ISO_8859_1;
  }

  public Optional<String> cabecalho(String nome) {
    return cabecalhos.primeiro(nome);
  }

  public int tamanho() {
    return corpo.length;
  }

  @Override
  public String toString() {
    return status + " " + motivo + " (" + corpo.length + " bytes)";
  }
}
